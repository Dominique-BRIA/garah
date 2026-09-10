package com.garah.api.commerce.domaine;

import com.garah.api.catalogue.domaine.InfoVenteVariante;
import com.garah.api.catalogue.domaine.ServiceTarification;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commerce.infra.PanierRepository;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.stock.domaine.ServiceStock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Le panier d'un client.
 *
 * <p>Trois principes, tous conséquences du fait qu'un panier <b>n'engage
 * rien</b> :</p>
 * <ol>
 *   <li>aucun stock n'est réservé — sinon un visiteur pourrait immobiliser
 *       tout le catalogue sans jamais payer ;</li>
 *   <li>aucun prix n'est figé — le panier affiche le tarif du jour ;</li>
 *   <li>on peut y mettre un article en rupture, mais on le <b>signale</b>.</li>
 * </ol>
 */
@Service
public class ServicePanier {

    private static final int QUANTITE_MAX_PAR_LIGNE = 1000;

    private final PanierRepository paniers;
    private final com.garah.api.commun.audit.JournalParcours parcours;
    private final VarianteRepository variantes;
    private final ServiceTarification tarification;
    private final com.garah.api.serviceclient.domaine.ServiceNegociation negociation;
    private final ServiceStock stock;

    public ServicePanier(com.garah.api.commun.audit.JournalParcours parcours,
                         PanierRepository paniers, VarianteRepository variantes,
                         ServiceTarification tarification, ServiceStock stock,
                         com.garah.api.serviceclient.domaine.ServiceNegociation negociation) {
        this.parcours = parcours;
        this.paniers = paniers;
        this.variantes = variantes;
        this.tarification = tarification;
        this.negociation = negociation;
        this.stock = stock;
    }

    /**
     * Le panier actif du client, créé au besoin.
     *
     * <p>L'index unique partiel {@code panier_actif_unique} garantit qu'il n'y
     * en a jamais deux. Le service n'a donc pas à s'en soucier.</p>
     */
    @Transactional
    public Panier panierActif(Long clientId) {
        return paniers.findByClientIdAndStatut(clientId, "ACTIF")
                .orElseGet(() -> paniers.save(new Panier(clientId)));
    }

    @Transactional
    public ContenuPanier ajouter(Long clientId, Long varianteId, int quantite) {
        if (quantite < 1 || quantite > QUANTITE_MAX_PAR_LIGNE) {
            throw new RegleMetierViolee("QUANTITE_INVALIDE",
                    "La quantité doit être comprise entre 1 et " + QUANTITE_MAX_PAR_LIGNE + ".");
        }

        InfoVenteVariante info = infoVente(varianteId);
        if (!info.estVendable()) {
            // On refuse d'ajouter un article non publié : sinon le client
            // découvrirait le problème seulement au paiement.
            throw new RegleMetierViolee("ARTICLE_INDISPONIBLE",
                    "Cet article n'est plus proposé à la vente.");
        }

        panierActif(clientId).ajouter(varianteId, quantite);

        // ⚠️ Après les deux refus ci-dessus : on journalise ce qui est ARRIVE
        //    au panier, pas ce qui a été tenté. Un journal de parcours qui
        //    contient les échecs ne raconte plus le parcours.
        parcours.ajoutAuPanier(varianteId, info.designation(), quantite);

        return contenu(clientId);
    }

    @Transactional
    public ContenuPanier definirQuantite(Long clientId, Long varianteId, int quantite) {
        if (quantite < 0) {
            throw new RegleMetierViolee("QUANTITE_INVALIDE",
                    "La quantité ne peut pas être négative.");
        }

        Panier panier = panierActif(clientId);
        if (quantite == 0) {
            panier.retirer(varianteId);   // zéro = retirer, comportement attendu
        } else {
            panier.ligneDe(varianteId)
                    .orElseThrow(() -> RessourceIntrouvable.de("Ligne de panier", varianteId))
                    .definirQuantite(quantite);
        }
        return contenu(clientId);
    }

    @Transactional
    public ContenuPanier retirer(Long clientId, Long varianteId) {
        panierActif(clientId).retirer(varianteId);
        return contenu(clientId);
    }

    @Transactional
    public ContenuPanier vider(Long clientId) {
        panierActif(clientId).vider();
        return contenu(clientId);
    }

    /**
     * Reprend le panier constitué <b>avant</b> la connexion.
     *
     * <h2>Pourquoi cette méthode existe</h2>
     *
     * <p>La vitrine est ouverte, le paiement non (D-07). Un visiteur remplit
     * donc son panier <b>dans son navigateur</b>, puis se connecte au moment de
     * commander. C'est ici que les deux paniers se rejoignent.</p>
     *
     * <p>Pousser les lignes une par une marcherait aussi. Sur l'axe
     * Douala → Bangui, un aller-retour par ligne se sent — et surtout, les
     * écarts arriveraient en ordre dispersé, ligne après ligne, au lieu d'être
     * annoncés d'un seul coup.</p>
     *
     * <h2>🎯 On garde LE PLUS GRAND des deux, et c'est délibéré</h2>
     *
     * <p>La plupart des boutiques <b>additionnent</b>. On ne le fait pas, pour
     * une raison qui tient au terrain : sur une connexion instable, une requête
     * est réémise. Une fusion additive rejouée <b>double les quantités</b>, et
     * le client ne s'en aperçoit qu'à la facture.</p>
     *
     * <p>Prendre le maximum rend l'opération <b>idempotente</b> : la rejouer dix
     * fois donne le même panier. C'est aussi le comportement juste
     * fonctionnellement — le visiteur non connecté ne voyait pas le panier du
     * serveur, il ne peut donc pas avoir voulu « ajouter » à quelque chose
     * qu'il ignorait.</p>
     *
     * <h2>Ce qu'on ne vérifie pas</h2>
     *
     * <p>⚠️ <b>Le stock n'est pas contrôlé ici</b>, exactement comme dans
     * {@link #ajouter}. La fusion n'est pas plus stricte qu'un ajout ordinaire,
     * sinon le même geste réussirait connecté et échouerait à la connexion.
     * Les ruptures sont signalées par {@link ContenuPanier}, à l'affichage,
     * comme pour tout autre panier.</p>
     *
     * <p>Une ligne refusée <b>n'interrompt pas</b> la fusion : les autres
     * passent, et l'écart est rapporté. Tout annuler pour un article dépublié
     * ferait perdre un panier entier.</p>
     */
    @Transactional
    public ResultatFusion fusionner(Long clientId, List<LigneLocale> locales) {
        Panier panier = panierActif(clientId);
        List<ResultatFusion.Ecart> ecarts = new ArrayList<>();

        for (LigneLocale locale : locales) {
            if (locale.quantite() < 1 || locale.quantite() > QUANTITE_MAX_PAR_LIGNE) {
                // Une quantité aberrante vient d'un stockage local corrompu ou
                // bricolé : on l'ignore sans faire échouer le reste.
                continue;
            }

            // ⚠️ Une variante DISPARUE doit être traitée comme indisponible,
            //    pas comme une erreur : un panier local peut dormir des
            //    semaines dans un navigateur, et le catalogue bouge. Laisser
            //    remonter RessourceIntrouvable ferait perdre tout le panier à
            //    cause d'une seule ligne périmée.
            InfoVenteVariante info;
            try {
                info = infoVente(locale.varianteId());
            } catch (RessourceIntrouvable disparue) {
                ecarts.add(new ResultatFusion.Ecart(locale.varianteId(), "Article retiré",
                        locale.quantite(), 0, ResultatFusion.Nature.INDISPONIBLE));
                continue;
            }

            if (!info.estVendable()) {
                ecarts.add(new ResultatFusion.Ecart(locale.varianteId(), info.designation(),
                        locale.quantite(), 0, ResultatFusion.Nature.INDISPONIBLE));
                continue;
            }

            LignePanier existante = panier.ligneDe(locale.varianteId()).orElse(null);

            if (existante == null) {
                panier.ajouter(locale.varianteId(), locale.quantite());
                continue;   // repris à l'identique : rien à signaler
            }

            if (existante.getQuantite() >= locale.quantite()) {
                ecarts.add(new ResultatFusion.Ecart(locale.varianteId(), info.designation(),
                        locale.quantite(), existante.getQuantite(),
                        ResultatFusion.Nature.DEJA_PLUS_GRANDE));
            } else {
                existante.definirQuantite(locale.quantite());
                ecarts.add(new ResultatFusion.Ecart(locale.varianteId(), info.designation(),
                        locale.quantite(), locale.quantite(),
                        ResultatFusion.Nature.RELEVEE));
            }
        }

        return new ResultatFusion(contenu(clientId), ecarts);
    }

    /** Une ligne telle que le navigateur la gardait. */
    public record LigneLocale(Long varianteId, int quantite) {
    }

    /**
     * Le contenu, avec les prix du jour et la disponibilité réelle.
     *
     * <p>C'est ici qu'on signale les ruptures. Découvrir « il n'en reste que
     * 2 » au moment de payer est la pire expérience possible : le client a
     * déjà sorti son téléphone.</p>
     */
    @Transactional(readOnly = true)
    public ContenuPanier contenu(Long clientId) {
        return paniers.chargerActifAvecLignes(clientId)
                .map(this::decrire)
                .orElseGet(ContenuPanier::vide);
    }

    private ContenuPanier decrire(Panier panier) {
        List<ContenuPanier.Ligne> lignes = new ArrayList<>();
        List<String> indisponibles = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int articles = 0;

        for (LignePanier ligne : panier.getLignes()) {
            InfoVenteVariante info = infoVente(ligne.getVarianteId());

            // 🎯 Le prix negocie se voit DES LE PANIER : le client doit lire
            //    le prix qu'il paiera avant de cliquer, pas le decouvrir apres.
            var negocie = negociation.prixNegocie(
                    panier.getClientId(), ligne.getVarianteId(), ligne.getQuantite());
            BigDecimal prix = negocie
                    .map(com.garah.api.serviceclient.domaine.PrixNegocie::prixUnitaire)
                    .orElseGet(() -> prixOuZero(ligne.getVarianteId(), ligne.getQuantite()));
            BigDecimal montant = prix.multiply(BigDecimal.valueOf(ligne.getQuantite()));
            int disponible = disponibleOuZero(ligne.getVarianteId());
            boolean vendable = info.estVendable() && disponible >= ligne.getQuantite();

            if (!vendable) {
                indisponibles.add(info.designation());
            }

            lignes.add(new ContenuPanier.Ligne(ligne.getVarianteId(), info.designation(),
                    ligne.getQuantite(), prix, montant, disponible, vendable,
                    negocie.isPresent()));

            total = total.add(montant);
            articles += ligne.getQuantite();
        }

        return new ContenuPanier(panier.getId(), lignes, total, articles, indisponibles);
    }

    private InfoVenteVariante infoVente(Long varianteId) {
        return variantes.infoVente(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Variante", varianteId));
    }

    /**
     * Un prix absent ne doit pas faire échouer l'affichage du panier.
     *
     * <p>La ligne apparaît à zéro et marquée non vendable, ce que le client
     * comprend. Lever une exception ici rendrait le panier <b>entier</b>
     * inaccessible à cause d'un seul article mal paramétré.</p>
     */
    private BigDecimal prixOuZero(Long varianteId, int quantite) {
        try {
            return tarification.prixUnitaire(varianteId, quantite);
        } catch (RegleMetierViolee absent) {
            return BigDecimal.ZERO;
        }
    }

    private int disponibleOuZero(Long varianteId) {
        try {
            return stock.etat(varianteId).disponible();
        } catch (RessourceIntrouvable absent) {
            return 0;
        }
    }
}
