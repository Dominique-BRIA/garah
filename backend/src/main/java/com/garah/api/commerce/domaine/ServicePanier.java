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
    private final VarianteRepository variantes;
    private final ServiceTarification tarification;
    private final ServiceStock stock;

    public ServicePanier(PanierRepository paniers, VarianteRepository variantes,
                         ServiceTarification tarification, ServiceStock stock) {
        this.paniers = paniers;
        this.variantes = variantes;
        this.tarification = tarification;
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

            BigDecimal prix = prixOuZero(ligne.getVarianteId(), ligne.getQuantite());
            BigDecimal montant = prix.multiply(BigDecimal.valueOf(ligne.getQuantite()));
            int disponible = disponibleOuZero(ligne.getVarianteId());
            boolean vendable = info.estVendable() && disponible >= ligne.getQuantite();

            if (!vendable) {
                indisponibles.add(info.designation());
            }

            lignes.add(new ContenuPanier.Ligne(ligne.getVarianteId(), info.designation(),
                    ligne.getQuantite(), prix, montant, disponible, vendable));

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
