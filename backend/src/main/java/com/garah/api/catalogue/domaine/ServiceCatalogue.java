package com.garah.api.catalogue.domaine;

import com.garah.api.catalogue.infra.*;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La gestion du catalogue.
 *
 * <p>C'est ici que vivent les règles que la base ne peut pas porter :
 * les <b>transitions d'état</b> et les conditions « au moins un ».</p>
 */
@Service
public class ServiceCatalogue {

    /**
     * Les transitions autorisées du produit (chapitre 04 §4).
     *
     * <p>Écrire la machine à états sous forme de <b>données</b> plutôt que de
     * cascades de {@code if} a deux avantages : elle se lit d'un coup d'œil,
     * et on ne peut pas en oublier une branche.</p>
     */
    private static final Map<StatutProduit, Set<StatutProduit>> TRANSITIONS = Map.of(
            StatutProduit.BROUILLON, Set.of(StatutProduit.PUBLIE, StatutProduit.ARCHIVE),
            StatutProduit.PUBLIE,    Set.of(StatutProduit.MASQUE, StatutProduit.ARCHIVE),
            StatutProduit.MASQUE,    Set.of(StatutProduit.PUBLIE, StatutProduit.ARCHIVE),
            StatutProduit.ARCHIVE,   Set.of());   // terminal : plus aucune sortie

    private final ProduitRepository produits;
    private final VarianteRepository variantes;
    private final CategorieProduitRepository categories;
    private final MediaRepository medias;
    private final TarificationRepository tarifications;

    public ServiceCatalogue(ProduitRepository produits,
                            VarianteRepository variantes,
                            CategorieProduitRepository categories,
                            MediaRepository medias,
                            TarificationRepository tarifications) {
        this.produits = produits;
        this.variantes = variantes;
        this.categories = categories;
        this.medias = medias;
        this.tarifications = tarifications;
    }

    /**
     * Crée un produit AVEC sa variante par défaut.
     *
     * <p>C'est le point clé de D-01 : on ne crée <b>jamais</b> un produit sans
     * variante. Même un sac de ciment en a une. Sinon tout le code aval devrait
     * tester {@code if (produit.aDesVariantes())}, et ce test finirait par être
     * oublié quelque part — au panier, à la commande ou au colis.</p>
     */
    @Transactional
    public DetailProduit creerProduit(Long marchandId, Long categorieId, String reference,
                                      String nom, Long creePar) {
        if (produits.existsByReference(reference)) {
            throw new RegleMetierViolee("REFERENCE_DEJA_UTILISEE",
                    "La référence " + reference + " est déjà utilisée par un autre produit.");
        }

        CategorieProduit categorie = categories.findById(categorieId)
                .orElseThrow(() -> RessourceIntrouvable.de("Catégorie", categorieId));

        Produit produit = new Produit(marchandId, categorie, reference, nom, creePar);
        produits.save(produit);

        // La variante par défaut. Son SKU dérive de la référence : tant qu'il
        // n'y a pas de déclinaison, les deux se confondent naturellement.
        produit.ajouterVariante(reference, nom, true);

        return DetailProduit.de(produit);
    }

    @Transactional
    public Variante ajouterVariante(Long produitId, String sku, String libelle,
                                    List<ValeurAttribut> valeurs) {
        Produit produit = produits.findById(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        if (variantes.existsBySku(sku)) {
            throw new RegleMetierViolee("SKU_DEJA_UTILISE",
                    "Le SKU " + sku + " est déjà utilisé.");
        }

        Variante variante = produit.ajouterVariante(sku, libelle, false);
        valeurs.forEach(variante::definirPar);
        return variante;
    }

    /**
     * Ajoute un média.
     *
     * <p>Si on le désigne comme principal, il faut <b>d'abord</b> retirer la
     * marque de l'ancien : l'index unique partiel {@code media_principal_unique}
     * refuse deux lignes à {@code true} pour le même produit.</p>
     */
    @Transactional
    public Media ajouterMedia(Long produitId, TypeMedia type, String cleObjet, boolean principal) {
        Produit produit = produits.findById(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        // Le tout premier média devient principal d'office : un produit sans
        // photo principale n'aurait rien à afficher en liste.
        boolean estPremier = medias.countByProduitId(produitId) == 0;
        boolean devientPrincipal = principal || estPremier;

        if (devientPrincipal) {
            medias.retirerPrincipal(produitId);
        }

        return produit.ajouterMedia(type, cleObjet, devientPrincipal);
    }

    /**
     * Publie un produit — l'opération la plus contrôlée du catalogue.
     *
     * <p>L'invariant I-12 ne peut PAS être une contrainte SQL : « au moins une
     * variante active », « au moins un prix », « au moins une photo » portent
     * sur des lignes qui n'existent pas encore au moment où le produit est
     * inséré. Ces trois règles vivent donc ici.</p>
     *
     * <p>Et elles ne sont pas décoratives : un produit publié sans prix
     * s'affiche dans le catalogue, se met au panier, et fait échouer la
     * commande au dernier moment — devant le client.</p>
     */
    @Transactional
    public DetailProduit publier(Long produitId) {
        return DetailProduit.de(publierEntite(produitId));
    }

    private Produit publierEntite(Long produitId) {
        Produit produit = produits.findById(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        verifierTransition(produit, StatutProduit.PUBLIE);

        if (variantes.countByProduitIdAndStatut(produitId, "ACTIVE") == 0) {
            throw new RegleMetierViolee("AUCUNE_VARIANTE_ACTIVE",
                    "Ce produit n'a aucune déclinaison active : il n'y a rien à vendre.");
        }
        if (!tarifications.produitAUnPrix(produitId)) {
            throw new RegleMetierViolee("AUCUN_PRIX",
                    "Ce produit n'a aucun prix : il serait invendable une fois publié.");
        }
        if (medias.countByProduitId(produitId) == 0) {
            throw new RegleMetierViolee("AUCUNE_PHOTO",
                    "Ce produit n'a aucune photo : il ne peut pas être présenté au client.");
        }

        produit.changerStatut(StatutProduit.PUBLIE);
        return produit;
    }

    @Transactional
    public DetailProduit changerStatut(Long produitId, StatutProduit nouveau) {
        if (nouveau == StatutProduit.PUBLIE) {
            return publier(produitId);   // la publication a ses propres contrôles
        }

        Produit produit = produits.findById(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        verifierTransition(produit, nouveau);
        produit.changerStatut(nouveau);
        return DetailProduit.de(produit);
    }

    // -------------------------------------------------------------------------
    // Lectures
    // -------------------------------------------------------------------------
    // Le service renvoie des VUES (des records), jamais des entités.
    //
    // Ce n'est pas de la cérémonie : le test ArchUnit « les entités JPA ne
    // sortent pas du domaine » échoue au build si un contrôleur importe une
    // entité. Ma première version du contrôleur le faisait — la règle a fait
    // son travail. Sans elle, un `mot_de_passe` ou un `cree_par` finit un jour
    // dans une réponse JSON.
    // -------------------------------------------------------------------------

    /** Le catalogue public : uniquement les produits publiés. */
    @Transactional(readOnly = true)
    public Page<ResumeProduit> catalogue(Long categorieId, Pageable pagination) {
        Page<Produit> resultats = (categorieId == null)
                ? produits.findByStatut(StatutProduit.PUBLIE, pagination)
                : produits.findByCategorieIdAndStatut(categorieId, StatutProduit.PUBLIE, pagination);

        return resultats.map(ResumeProduit::de);
    }

    /**
     * La fiche publique d'un produit.
     *
     * <p>Un brouillon reste inaccessible même si on connaît son slug, et le
     * message est celui d'un produit <b>absent</b> : confirmer son existence
     * renseignerait un concurrent sur le catalogue à venir.</p>
     */
    @Transactional(readOnly = true)
    public DetailProduit fichePublique(String slug) {
        Produit produit = produits.findBySlug(slug)
                .filter(Produit::estPublie)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", slug));

        return DetailProduit.de(produit);
    }

    /**
     * La fiche complète, brouillons compris — réservée au back-office.
     *
     * <p>Deux requêtes, et c'est <b>obligatoire</b> : Hibernate refuse de
     * charger deux collections de type {@code List} dans une seule requête
     * (voir {@code ProduitRepository}). Le second appel ne renvoie pas un autre
     * objet — il complète celui qui est déjà dans la transaction.</p>
     */
    @Transactional(readOnly = true)
    public DetailProduit ficheAdministration(Long produitId) {
        Produit produit = produits.chargerAvecVariantes(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        // Même instance gérée : cette requête ne fait qu'initialiser `medias`.
        produits.chargerAvecMedias(produitId);

        return DetailProduit.de(produit);
    }

    private void verifierTransition(Produit produit, StatutProduit vers) {
        if (!TRANSITIONS.get(produit.getStatut()).contains(vers)) {
            throw ConflitEtat.transitionInterdite(
                    "le produit " + produit.getReference(), produit.getStatut().name(), vers.name());
        }
    }
}
