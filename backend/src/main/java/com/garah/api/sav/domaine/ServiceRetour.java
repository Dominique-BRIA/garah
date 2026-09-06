package com.garah.api.sav.domaine;

import com.garah.api.commerce.domaine.LigneCommande;
import com.garah.api.commerce.domaine.MoyenPaiement;
import com.garah.api.commerce.domaine.ServicePaiement;
import com.garah.api.commerce.infra.LigneCommandeRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.sav.infra.RetourRepository;
import com.garah.api.stock.domaine.ServiceStock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Les retours de marchandise.
 *
 * <p>La correction A4 disait : <b>« un retour est une opération, pas un
 * post-it »</b>. Ce service en est la démonstration — sa validation écrit dans
 * quatre domaines, en une seule transaction.</p>
 */
@Service
public class ServiceRetour {

    /** Transitions autorisées (chapitre 04 §4.4). */
    private static final Map<StatutRetour, Set<StatutRetour>> TRANSITIONS = Map.of(
            StatutRetour.DEMANDE,     Set.of(StatutRetour.ACCEPTE, StatutRetour.REFUSE),
            StatutRetour.ACCEPTE,     Set.of(StatutRetour.RECEPTIONNE, StatutRetour.REFUSE),
            StatutRetour.RECEPTIONNE, Set.of(StatutRetour.VALIDE),
            StatutRetour.VALIDE,      Set.of(StatutRetour.CLOTURE),
            StatutRetour.REFUSE,      Set.of(),
            StatutRetour.CLOTURE,     Set.of());

    private final RetourRepository retours;
    private final LigneCommandeRepository lignesCommande;
    private final ServiceStock stock;
    private final ServicePaiement paiements;

    public ServiceRetour(RetourRepository retours, LigneCommandeRepository lignesCommande,
                         ServiceStock stock, ServicePaiement paiements) {
        this.retours = retours;
        this.lignesCommande = lignesCommande;
        this.stock = stock;
        this.paiements = paiements;
    }

    /**
     * Le client demande à retourner des articles.
     *
     * <p>À ce stade, <b>rien</b> n'est écrit ailleurs : ni stock, ni
     * remboursement. Un retour demandé n'est qu'une intention — la marchandise
     * n'est même pas encore revenue.</p>
     *
     * <p>Le trigger {@code ligne_retour_quantite_trigger} (I-40) empêche de
     * retourner plus qu'on n'a acheté, en tenant compte des retours
     * <b>précédents</b> : un client peut renvoyer 2 articles en mars et 2 en
     * avril sur les 3 achetés — le second sera refusé par la base.</p>
     */
    @Transactional
    public Retour demander(Long commandeId, Long clientId, String motif,
                           List<DemandeLigne> lignes) {
        if (lignes == null || lignes.isEmpty()) {
            throw new RegleMetierViolee("RETOUR_VIDE",
                    "Un retour doit porter sur au moins un article.");
        }

        Retour retour = new Retour(genererNumero(), commandeId, clientId, motif);
        retours.save(retour);

        for (DemandeLigne demande : lignes) {
            LigneCommande ligne = lignesCommande.findById(demande.ligneCommandeId())
                    .orElseThrow(() -> RessourceIntrouvable.de(
                            "Ligne de commande", demande.ligneCommandeId()));

            if (!ligne.getCommande().getId().equals(commandeId)) {
                // Sans ce contrôle, un client pourrait retourner l'article
                // d'une commande qui n'est pas la sienne.
                throw new RegleMetierViolee("LIGNE_ETRANGERE",
                        "Cette ligne n'appartient pas à la commande indiquée.");
            }
            if (demande.quantite() < 1) {
                throw new RegleMetierViolee("QUANTITE_INVALIDE",
                        "Une ligne de retour porte sur au moins une unité.");
            }

            retour.ajouterLigne(demande.ligneCommandeId(), demande.quantite(), demande.etat());
        }

        return retour;
    }

    @Transactional
    public Retour accepter(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.ACCEPTE);
        retour.accepter();
        return retour;
    }

    @Transactional
    public Retour refuser(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.REFUSE);
        retour.refuser();
        return retour;
    }

    /** Les articles sont physiquement revenus. Toujours rien en stock. */
    @Transactional
    public Retour receptionner(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.RECEPTIONNE);
        retour.receptionner();
        return retour;
    }

    /**
     * <b>L'opération centrale du module</b>, et l'exemple du chapitre 04 §11.
     *
     * <p>Elle écrit dans quatre domaines, en une seule transaction :</p>
     *
     * <pre>
     * 1. le retour passe à VALIDE
     * 2. pour chaque ligne, un MOUVEMENT DE STOCK
     *       NEUF  → compteur DISPONIBLE   (revendable)
     *       ABIMÉ → compteur ENDOMMAGEE   (jamais revendu)
     * 3. le montant remboursé est calculé et figé sur chaque ligne
     * 4. un PAIEMENT de type REMBOURSEMENT est créé
     * 5. les écritures marchand              ← chapitre 17
     * </pre>
     *
     * <p>⚠️ <b>Si l'une échoue, aucune ne doit rester.</b> Un retour à moitié
     * enregistré — stock remis mais client non remboursé — est pire que pas de
     * retour du tout, parce que personne ne s'en apercevra.</p>
     *
     * <p>Et c'est seulement <b>ici</b> que le remboursement part : après que
     * quelqu'un a vu et contrôlé la marchandise. Jamais à la demande.</p>
     */
    @Transactional
    public Retour valider(Long retourId, MoyenPaiement moyen) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.VALIDE);

        BigDecimal totalARembourser = BigDecimal.ZERO;

        for (LigneRetour ligneRetour : retour.getLignes()) {
            LigneCommande ligneCommande = lignesCommande.findById(ligneRetour.getLigneCommandeId())
                    .orElseThrow(() -> RessourceIntrouvable.de(
                            "Ligne de commande", ligneRetour.getLigneCommandeId()));

            // 2. Le mouvement de stock dépend de l'ÉTAT de l'article.
            stock.retour(ligneCommande.getVarianteId(), ligneRetour.getQuantite(),
                    retour.getId(), ligneRetour.getEtatArticle().estRevendable());

            // 3. On rembourse au prix RÉELLEMENT PAYÉ, pas au prix du jour.
            //    C'est la règle de la photographie qui rend ce calcul possible.
            BigDecimal montant = ligneCommande.getPrixUnitaire()
                    .multiply(BigDecimal.valueOf(ligneRetour.getQuantite()))
                    .setScale(2, RoundingMode.HALF_UP);

            ligneRetour.definirRemboursement(montant);
            totalARembourser = totalARembourser.add(montant);
        }

        // 4. Un seul remboursement pour tout le retour : le client reçoit un
        //    virement, pas quatre.
        if (totalARembourser.signum() > 0) {
            paiements.rembourser(retour.getCommandeId(), totalARembourser, moyen,
                    "RETOUR", retour.getId());
        }

        // TODO chapitre 17 : écritures RETOUR et ANNUL_COMMISSION du grand
        // livre marchand. Le retour annule la vente ET la commission.

        retour.valider();
        return retour;
    }

    @Transactional
    public Retour cloturer(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.CLOTURE);
        retour.cloturer();
        return retour;
    }

    @Transactional(readOnly = true)
    public List<Retour> pourCommande(Long commandeId) {
        return retours.findByCommandeId(commandeId);
    }

    /** Ce que le client demande de retourner. */
    public record DemandeLigne(Long ligneCommandeId, int quantite, EtatArticle etat) {
    }

    private String genererNumero() {
        return "RET-%d-%06d".formatted(Year.now().getValue(), retours.prochainNumero());
    }

    private Retour charger(Long retourId) {
        return retours.chargerAvecLignes(retourId)
                .orElseThrow(() -> RessourceIntrouvable.de("Retour", retourId));
    }

    private void verifierTransition(Retour retour, StatutRetour vers) {
        if (!TRANSITIONS.get(retour.getStatut()).contains(vers)) {
            throw ConflitEtat.transitionInterdite(
                    "le retour " + retour.getNumero(), retour.getStatut().name(), vers.name());
        }
    }
}
