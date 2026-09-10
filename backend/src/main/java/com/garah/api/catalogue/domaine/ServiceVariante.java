package com.garah.api.catalogue.domaine;

import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Les variantes vues du back-office, avec leur grille tarifaire.
 *
 * <p>Surcouche mince sur {@link ServiceCatalogue} et {@link ServiceTarification} :
 * elle assemble ce que l'ecran de gestion d'un produit affiche d'un seul tenant,
 * et convertit en DTO — la couche web n'a pas le droit de toucher une entite.</p>
 */
@Service
public class ServiceVariante {

    private final ServiceCatalogue catalogue;
    private final ServiceTarification tarification;
    private final VarianteRepository variantes;

    public ServiceVariante(ServiceCatalogue catalogue, ServiceTarification tarification,
                           VarianteRepository variantes) {
        this.catalogue = catalogue;
        this.tarification = tarification;
        this.variantes = variantes;
    }

    /**
     * ⚠️ Plus de {@code sku} en paramètre : il est ENGENDRÉ à partir de la
     * référence du produit et de l'intitulé. Saisi, il divergeait — « Adidas
     * 42 » portait la référence {@code BL460}, qui ne se rattache à rien.
     */
    @Transactional
    public VueVariante ajouter(Long produitId, String libelle) {
        Variante variante = catalogue.ajouterVariante(produitId, libelle, List.of());
        return VueVariante.de(variante, produitId, List.of());
    }

    /**
     * Cree toutes les declinaisons d'une grille de valeurs.
     *
     * <pre>
     * Taille  42, 43     →  42 — Blanc   43 — Blanc
     * Couleur Blanc, Noir   42 — Noir    43 — Noir
     * </pre>
     *
     * <p>SKU et intitules sont COMPOSES a partir des valeurs, jamais saisis :
     * quatre declinaisons creees une par une, ce sont quatre occasions
     * d'ecrire l'intitule differemment.</p>
     *
     * <p>Les combinaisons deja presentes sont ignorees en silence. Ajouter la
     * couleur « Rouge » a un produit qui a deja 42-Blanc doit creer 42-Rouge
     * sans se plaindre du reste.</p>
     */
    @Transactional
    public List<VueVariante> creerGrille(Long produitId, List<List<Long>> valeursParAttribut) {
        return catalogue.creerGrille(produitId, valeursParAttribut).stream()
                // Les paliers sont vides : une declinaison qui vient de naitre
                // n'a pas encore de prix. C'est justement ce que l'ecran doit
                // montrer comme manquant.
                .map(v -> VueVariante.de(v, produitId, List.of()))
                .toList();
    }

    /**
     * Les variantes d'un produit, chacune avec sa grille.
     *
     * <p>⚠️ Tout est lu DANS la transaction. La grille est chargee ici, et non
     * a la serialisation : avec {@code open-in-view: false}, un acces paresseux
     * hors transaction leverait — et seulement a l'execution.</p>
     */
    @Transactional(readOnly = true)
    public List<VueVariante> lister(Long produitId) {
        return variantes.findByProduitId(produitId).stream()
                .map(v -> VueVariante.de(v, produitId, tarification.grille(v.getId())))
                .toList();
    }

    @Transactional
    public VueVariante definirPalier(Long varianteId, int quantiteMin, Integer quantiteMax,
                                     BigDecimal prixUnitaire) {
        tarification.definirPalier(varianteId, quantiteMin, quantiteMax, prixUnitaire);
        return recharger(varianteId);
    }

    /**
     * Corrige le SKU et l'intitule d'une declinaison.
     *
     * <p>Le SKU est modifiable, et ce n'est pas anodin : c'est le code qu'on
     * lit sur un bordereau de colisage et qu'on rapproche du stock. On
     * l'autorise quand meme, parce que l'alternative est pire — une erreur de
     * saisie au clavier resterait gravee pour toujours, et l'utilisateur
     * n'aurait d'autre issue que de creer une seconde declinaison en double.</p>
     *
     * <p>L'unicite est verifiee <b>ici</b> pour produire un message lisible, et
     * <b>aussi</b> par la contrainte {@code UNIQUE} de la base pour qu'aucun
     * chemin ne l'evite.</p>
     */
    @Transactional
    /**
     * Corrige l'intitule d'une declinaison.
     *
     * <h2>⚠️ LE SKU NE SE MODIFIE PLUS</h2>
     *
     * <p>Il est engendre a la creation, et il <b>identifie</b> : il figure sur
     * les bordereaux, dans les mouvements de stock, dans les lignes de
     * commande deja passees. Le changer romprait le lien avec tout ce qui le
     * cite — et ces documents-la ne se reecrivent pas.</p>
     *
     * <p>L'intitule, lui, DECRIT : il se corrige librement. C'est la meme
     * repartition que {@code CompositionVariante} enonce pour la grille : « le
     * SKU reste (il identifie), l'intitule se recalcule (il decrit) ».</p>
     */
    public VueVariante modifier(Long varianteId, String libelle) {
        Variante variante = variantes.findById(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Variante", varianteId));

        variante.setLibelle(libelle);
        return recharger(varianteId);
    }

    /**
     * Active ou desactive une declinaison.
     *
     * <p>⚠️ Desactiver la <b>derniere</b> declinaison active d'un produit
     * publie est refuse. Sans ce garde-fou, le produit resterait en vitrine
     * sans rien a vendre : le client le verrait, cliquerait, et ne pourrait
     * rien mettre au panier. L'invariant I-12 est verifie a la publication ;
     * il faut aussi qu'on ne puisse pas le violer <b>apres</b>.</p>
     */
    @Transactional
    public VueVariante changerStatut(Long varianteId, boolean actif) {
        Variante variante = variantes.findById(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Variante", varianteId));

        Produit produit = variante.getProduit();

        if (!actif && produit.getStatut() == StatutProduit.PUBLIE
                && variantes.countByProduitIdAndStatut(produit.getId(), "ACTIVE") <= 1) {
            throw new RegleMetierViolee("DERNIERE_VARIANTE_ACTIVE",
                    "C'est la seule declinaison active d'un produit publie : "
                    + "le retirer laisserait un produit en vente sans rien a vendre. "
                    + "Depubliez le produit d'abord.");
        }

        variante.setStatut(actif ? "ACTIVE" : "INACTIVE");
        return recharger(varianteId);
    }

    /** Change le prix d'un palier existant. L'historique est conserve. */
    @Transactional
    public VueVariante changerPrix(Long varianteId, Long palierId, BigDecimal nouveauPrix) {
        verifierAppartenance(varianteId, palierId);
        tarification.changerPrix(palierId, nouveauPrix);
        return recharger(varianteId);
    }

    /** Retire un palier de la grille. */
    @Transactional
    public VueVariante supprimerPalier(Long varianteId, Long palierId) {
        verifierAppartenance(varianteId, palierId);
        tarification.supprimerPalier(palierId);
        return recharger(varianteId);
    }

    /**
     * Le palier appartient-il bien a cette declinaison ?
     *
     * <p>⚠️ Sans ce controle, l'identifiant du palier suffirait a modifier le
     * prix d'une <b>autre</b> declinaison — celle d'un autre marchand, le cas
     * echeant. Le chemin de la route ne prouve rien a lui seul : il annonce
     * une declinaison, il ne verifie pas que le palier en fait partie.</p>
     */
    private void verifierAppartenance(Long varianteId, Long palierId) {
        boolean sien = tarification.grille(varianteId).stream()
                .anyMatch(p -> palierId.equals(p.id()));

        if (!sien) {
            throw RessourceIntrouvable.de("Palier de prix", palierId);
        }
    }

    @Transactional(readOnly = true)
    public VueVariante detail(Long varianteId) {
        return recharger(varianteId);
    }

    private VueVariante recharger(Long varianteId) {
        Variante variante = variantes.findById(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Variante", varianteId));

        return VueVariante.de(variante, variante.getProduit().getId(),
                tarification.grille(varianteId));
    }
}
