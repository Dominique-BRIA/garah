package com.garah.api.stock.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.stock.infra.MouvementStockRepository;
import com.garah.api.stock.infra.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Le module le plus délicat du backend.
 *
 * <p>Pas à cause du métier — compter des unités est simple — mais à cause de
 * la <b>concurrence</b> : deux clients peuvent acheter le dernier article à la
 * même milliseconde.</p>
 *
 * <p>Chaque opération suit le même schéma en quatre temps :</p>
 * <ol>
 *   <li>verrouiller la ligne de stock ({@code SELECT … FOR UPDATE}) ;</li>
 *   <li>vérifier que l'opération est possible ;</li>
 *   <li>appliquer la variation aux compteurs ;</li>
 *   <li>écrire le ou les mouvements correspondants.</li>
 * </ol>
 *
 * <p>Les quatre étapes sont dans <b>une seule transaction</b>. Sans ça, on
 * pourrait réserver du stock pour une commande qui n'existera jamais — et
 * personne ne s'en apercevrait avant l'inventaire.</p>
 */
@Service
public class ServiceStock {

    private final StockRepository stocks;
    private final MouvementStockRepository mouvements;

    public ServiceStock(StockRepository stocks, MouvementStockRepository mouvements) {
        this.stocks = stocks;
        this.mouvements = mouvements;
    }

    /**
     * Crée la ligne de stock d'une variante, à zéro.
     *
     * <p>Elle doit exister <b>avant</b> toute opération : l'invariant I-15 dit
     * « une variante, exactement un stock ». Une variante sans stock ferait
     * échouer la première commande, longtemps après sa création.</p>
     */
    @Transactional
    public EtatStock creerPour(Long varianteId) {
        if (stocks.findByVarianteId(varianteId).isPresent()) {
            throw new RegleMetierViolee("STOCK_DEJA_CREE",
                    "Cette variante a déjà un stock.");
        }
        return EtatStock.de(stocks.save(new Stock(varianteId)));
    }

    // -------------------------------------------------------------------------
    // Entrées et sorties physiques
    // -------------------------------------------------------------------------

    /** Réception de marchandise. */
    @Transactional
    public EtatStock entrer(Long varianteId, int quantite, Long responsableId, String commentaire) {
        exigerQuantitePositive(quantite);
        Stock stock = verrouiller(varianteId);

        appliquer(stock, TypeMouvement.ENTREE, CompteurStock.DISPONIBLE, quantite,
                OrigineMouvement.MANUEL, null, responsableId, commentaire);

        return EtatStock.de(stock);
    }

    /**
     * Réserve du stock pour une commande en attente de paiement.
     *
     * <p><b>Pourquoi réserver plutôt que décrémenter ?</b> Parce que le mobile
     * money est asynchrone (D-06) : entre le moment où le client valide sur
     * son téléphone et la confirmation de l'opérateur, il peut s'écouler
     * plusieurs minutes. Pendant ce temps, la marchandise ne doit être ni
     * vendue à quelqu'un d'autre, ni considérée comme vendue.</p>
     *
     * <p>La réservation touche deux compteurs, donc écrit <b>deux</b>
     * mouvements (V16).</p>
     */
    @Transactional
    public EtatStock reserver(Long varianteId, int quantite, Long commandeId) {
        exigerQuantitePositive(quantite);
        Stock stock = verrouiller(varianteId);

        if (stock.getQuantiteDisponible() < quantite) {
            // 409 et non 400 : le client n'a rien fait de mal, c'est le monde
            // qui a changé depuis l'affichage de la page (chapitre 06 §5.1).
            throw new ConflitEtat("STOCK_INSUFFISANT",
                    "Il ne reste que " + stock.getQuantiteDisponible() + " unité(s) disponible(s).");
        }

        appliquer(stock, TypeMouvement.RESERVATION, CompteurStock.DISPONIBLE, -quantite,
                OrigineMouvement.COMMANDE, commandeId, null, null);
        appliquer(stock, TypeMouvement.RESERVATION, CompteurStock.RESERVEE, quantite,
                OrigineMouvement.COMMANDE, commandeId, null, null);

        return EtatStock.de(stock);
    }

    /**
     * Libère une réservation : commande annulée, ou paiement jamais confirmé.
     *
     * <p>C'est l'opération qu'un travail périodique déclenche sur les commandes
     * restées en attente de paiement. Sans elle, un client qui abandonne son
     * paiement immobilise la marchandise <b>pour toujours</b> — et le stock
     * disponible fond sans que personne ne comprenne pourquoi.</p>
     */
    @Transactional
    public EtatStock liberer(Long varianteId, int quantite, Long commandeId) {
        exigerQuantitePositive(quantite);
        Stock stock = verrouiller(varianteId);

        if (stock.getQuantiteReservee() < quantite) {
            throw new ConflitEtat("RESERVATION_INTROUVABLE",
                    "Il n'y a que " + stock.getQuantiteReservee() + " unité(s) réservée(s) à libérer.");
        }

        appliquer(stock, TypeMouvement.LIBERATION, CompteurStock.RESERVEE, -quantite,
                OrigineMouvement.COMMANDE, commandeId, null, null);
        appliquer(stock, TypeMouvement.LIBERATION, CompteurStock.DISPONIBLE, quantite,
                OrigineMouvement.COMMANDE, commandeId, null, null);

        return EtatStock.de(stock);
    }

    /**
     * La marchandise quitte l'entrepôt : le paiement est confirmé.
     *
     * <p>Un seul compteur bouge — {@code RESERVEE} — parce que
     * {@code DISPONIBLE} a déjà été décrémenté à la réservation. Le stock
     * physique diminue enfin réellement.</p>
     */
    @Transactional
    public EtatStock confirmerSortie(Long varianteId, int quantite, Long commandeId) {
        exigerQuantitePositive(quantite);
        Stock stock = verrouiller(varianteId);

        if (stock.getQuantiteReservee() < quantite) {
            throw new ConflitEtat("RESERVATION_INSUFFISANTE",
                    "La quantité réservée ne couvre pas cette sortie.");
        }

        appliquer(stock, TypeMouvement.SORTIE, CompteurStock.RESERVEE, -quantite,
                OrigineMouvement.COMMANDE, commandeId, null, null);

        return EtatStock.de(stock);
    }

    /**
     * Retour client.
     *
     * <p>Un article en bon état redevient vendable ; un article abîmé rejoint
     * le compteur {@code ENDOMMAGEE}. C'est ce qui empêche de revendre par
     * erreur une marchandise inutilisable — et la §13 de la spécification le
     * demandait explicitement.</p>
     */
    @Transactional
    public EtatStock retour(Long varianteId, int quantite, Long retourId, boolean bonEtat) {
        exigerQuantitePositive(quantite);
        Stock stock = verrouiller(varianteId);

        if (bonEtat) {
            appliquer(stock, TypeMouvement.RETOUR, CompteurStock.DISPONIBLE, quantite,
                    OrigineMouvement.RETOUR, retourId, null, "Retour en bon état");
        } else {
            appliquer(stock, TypeMouvement.CASSE, CompteurStock.ENDOMMAGEE, quantite,
                    OrigineMouvement.RETOUR, retourId, null, "Retour abîmé, invendable");
        }

        return EtatStock.de(stock);
    }

    /**
     * Correction après inventaire.
     *
     * <p>Le commentaire est <b>obligatoire</b> : un ajustement sans
     * justification est indiscernable d'un vol. C'est la seule opération qui
     * peut faire varier le stock sans cause métier, donc la seule qui doit
     * s'expliquer.</p>
     */
    @Transactional
    public EtatStock ajuster(Long varianteId, int quantiteReelle, Long responsableId, String motif) {
        if (quantiteReelle < 0) {
            throw new RegleMetierViolee("QUANTITE_INVALIDE",
                    "Une quantité constatée ne peut pas être négative.");
        }
        if (motif == null || motif.isBlank()) {
            throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
                    "Un ajustement de stock doit être justifié.");
        }

        Stock stock = verrouiller(varianteId);
        int ecart = quantiteReelle - stock.getQuantiteDisponible();

        if (ecart == 0) {
            return EtatStock.de(stock);   // rien à écrire : un mouvement nul est interdit
        }

        appliquer(stock, TypeMouvement.AJUSTEMENT, CompteurStock.DISPONIBLE, ecart,
                OrigineMouvement.INVENTAIRE, null, responsableId, motif);

        return EtatStock.de(stock);
    }

    // -------------------------------------------------------------------------
    // Lectures
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public EtatStock etat(Long varianteId) {
        return EtatStock.de(stocks.findByVarianteId(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Stock de la variante", varianteId)));
    }

    /**
     * Vérifie que l'état stocké correspond à la somme de son journal.
     *
     * <p>À faire tourner chaque nuit. Un écart signifie qu'une écriture a eu
     * lieu hors du service — script d'import, correction manuelle en base,
     * ou bug. Mieux vaut l'apprendre par une alerte que par un inventaire six
     * mois plus tard.</p>
     */
    @Transactional(readOnly = true)
    public boolean estReconcilie(Long varianteId) {
        Stock stock = stocks.findByVarianteId(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Stock de la variante", varianteId));

        return stock.getQuantiteDisponible()
                    == mouvements.recalculer(stock.getId(), CompteurStock.DISPONIBLE)
            && stock.getQuantiteReservee()
                    == mouvements.recalculer(stock.getId(), CompteurStock.RESERVEE)
            && stock.getQuantiteEndommagee()
                    == mouvements.recalculer(stock.getId(), CompteurStock.ENDOMMAGEE);
    }

    @Transactional(readOnly = true)
    public List<EtatStock> alertes() {
        return stocks.sousLeSeuil().stream().map(EtatStock::de).toList();
    }

    // -------------------------------------------------------------------------

    private Stock verrouiller(Long varianteId) {
        return stocks.verrouiller(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Stock de la variante", varianteId));
    }

    private void exigerQuantitePositive(int quantite) {
        if (quantite < 1) {
            throw new RegleMetierViolee("QUANTITE_INVALIDE",
                    "La quantité doit être d'au moins 1.");
        }
    }

    /** Applique la variation ET journalise, toujours ensemble. */
    private void appliquer(Stock stock, TypeMouvement type, CompteurStock compteur, int variation,
                           OrigineMouvement origineType, Long origineId,
                           Long responsableId, String commentaire) {
        int avant = stock.valeur(compteur);
        stock.appliquer(compteur, variation);

        mouvements.save(new MouvementStock(stock.getId(), type, compteur, variation,
                avant, origineType, origineId, responsableId, commentaire));
    }
}
