package com.garah.api.catalogue.domaine;

import com.garah.api.catalogue.infra.*;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.commun.stockage.StockageObjet;
import com.garah.api.marchand.domaine.ServiceMarchand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * Traduit les clés d'objet en adresses affichables.
     *
     * <p>Depuis D-21, le catalogue ne peut plus se contenter de renvoyer des
     * clés : avec un bucket privé, seul le serveur peut fabriquer une URL
     * lisible, puisqu'il faut la signer.</p>
     */
    private final StockageObjet urlsMedias;

    /**
     * Le marchand, en lecture seule, pour engendrer la référence d'un produit.
     *
     * <p>Le catalogue dépend du marchand, jamais l'inverse : le test ArchUnit
     * « aucun cycle entre les domaines » le vérifie au build.</p>
     */
    private final ServiceMarchand marchands;

    public ServiceCatalogue(ProduitRepository produits,
                            VarianteRepository variantes,
                            CategorieProduitRepository categories,
                            MediaRepository medias,
                            TarificationRepository tarifications,
                            StockageObjet urlsMedias,
                            ServiceMarchand marchands) {
        this.produits = produits;
        this.variantes = variantes;
        this.categories = categories;
        this.medias = medias;
        this.tarifications = tarifications;
        this.urlsMedias = urlsMedias;
        this.marchands = marchands;
    }

    /**
     * Crée un produit, sa référence étant <b>engendrée</b>.
     *
     * <p>C'est le chemin qu'emprunte le back-office. La référence se déduit du
     * marchand, de la catégorie et du nom : {@code 202020-CHA-ADIDAS}. Saisie à
     * la main, elle devenait ce que la personne avait sous les yeux ce jour-là
     * — « AD20 », « test2 » — et ne disait plus rien trois mois plus tard.</p>
     */
    @Transactional
    public DetailProduit creerProduit(Long marchandId, Long categorieId,
                                      String nom, Long creePar) {
        CategorieProduit categorie = categories.findById(categorieId)
                .orElseThrow(() -> RessourceIntrouvable.de("Catégorie", categorieId));

        // Le détail du marchand sert deux fois : il fournit le code, et il
        // échoue tout de suite si le marchand n'existe pas — plutôt que de
        // laisser la clé étrangère refuser l'insertion après coup.
        String code = marchands.detail(marchandId).code();

        String reference = referenceLibre(
                ReferenceProduit.de(code, categorie.getNom(), nom));

        return creer(marchandId, categorie, reference, nom, creePar);
    }

    /**
     * Crée un produit avec une référence <b>imposée</b>.
     *
     * <p>Réservé aux imports et aux tests, qui ont besoin d'une référence
     * connue d'avance. Le back-office passe par la variante qui l'engendre.</p>
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

        return creer(marchandId, categorie, reference, nom, creePar);
    }

    /**
     * Le tronc commun : le produit ET sa variante par défaut.
     *
     * <p>C'est le point clé de D-01 : on ne crée <b>jamais</b> un produit sans
     * variante. Même un sac de ciment en a une. Sinon tout le code aval devrait
     * tester {@code if (produit.aDesVariantes())}, et ce test finirait par être
     * oublié quelque part — au panier, à la commande ou au colis.</p>
     */
    private DetailProduit creer(Long marchandId, CategorieProduit categorie, String reference,
                                String nom, Long creePar) {
        Produit produit = new Produit(marchandId, categorie, reference, nom, creePar);

        // ⚠️ Le slug est calculé depuis le NOM, et deux produits peuvent porter
        // le même nom. Sans ce garde-fou, le second heurtait la contrainte
        // d'unicité et l'utilisateur recevait « conflit avec des données
        // existantes » — un message qui ne dit ni quoi ni comment le corriger.
        produit.definirSlug(slugLibre(produit.getSlug()));

        produits.save(produit);

        // La variante par défaut. Son SKU dérive de la référence : tant qu'il
        // n'y a pas de déclinaison, les deux se confondent naturellement.
        produit.ajouterVariante(reference, nom, true);

        return DetailProduit.de(produit, urlsMedias::urlPublique);
    }

    /**
     * La première référence libre à partir de cette base.
     *
     * <p>Deux « Adidas » du même marchand dans la même catégorie donnent la
     * même base : le second devient {@code …-ADIDAS-2}. On vérifie aussi les
     * SKU, car la référence sert de SKU à la variante par défaut — une
     * référence libre côté produit mais prise côté déclinaison échouerait à
     * l'insertion.</p>
     */
    private String referenceLibre(String base) {
        if (estLibre(base)) {
            return base;
        }
        for (int rang = 2; rang <= 999; rang++) {
            String candidat = base + "-" + rang;
            if (estLibre(candidat)) {
                return candidat;
            }
        }
        throw new RegleMetierViolee("REFERENCE_INTROUVABLE",
                "Trop de produits portent déjà ce nom chez ce marchand. "
                + "Précisez le nom du produit.");
    }

    private boolean estLibre(String reference) {
        return !produits.existsByReference(reference) && !variantes.existsBySku(reference);
    }

    private String slugLibre(String base) {
        if (!produits.existsBySlug(base)) {
            return base;
        }
        for (int rang = 2; rang <= 999; rang++) {
            String candidat = base + "-" + rang;
            if (!produits.existsBySlug(candidat)) {
                return candidat;
            }
        }
        throw new RegleMetierViolee("ADRESSE_INTROUVABLE",
                "Trop de produits portent déjà ce nom. Précisez-le.");
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
     * Les médias d'un produit, du principal au dernier.
     *
     * <p>Lecture transactionnelle : {@code open-in-view} est à {@code false},
     * donc tout ce qui doit être lu l'est ici, pas à la sérialisation.</p>
     */
    @Transactional(readOnly = true)
    public List<Media> mediasDe(Long produitId) {
        return medias.findByProduitIdOrderByOrdreAsc(produitId);
    }

    /**
     * Retire un média du catalogue et renvoie sa clé d'objet.
     *
     * <p>La clé est renvoyée pour que l'appelant puisse supprimer le fichier
     * <b>après</b> la transaction. La lire après coup sur une entité détachée
     * marcherait ici, mais dépendre de ce détail est exactement le genre de
     * fragilité qui casse au premier changement de mapping.</p>
     *
     * <p>⚠️ Supprimer la photo principale d'un produit publié le laisse sans
     * photo principale — l'invariant I-12 n'est vérifié qu'à la publication.
     * Le back-office doit donc en désigner une autre ; à défaut, la liste du
     * catalogue affichera ce produit sans vignette.</p>
     */
    @Transactional
    public String detacherMedia(Long mediaId) {
        Media media = medias.findById(mediaId)
                .orElseThrow(() -> RessourceIntrouvable.de("Média", mediaId));

        String cle = media.getCleObjet();
        medias.delete(media);
        return cle;
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
        return DetailProduit.de(publierEntite(produitId), urlsMedias::urlPublique);
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

    /**
     * Corrige la fiche d'un produit.
     *
     * <p><b>Le slug ne bouge pas.</b> Il est calculé une fois, à la création,
     * et {@link Produit} n'expose volontairement aucun moyen de le changer.
     * C'est l'adresse publique du produit : la recalculer à chaque
     * renommage transformerait tout lien déjà partagé — dans une conversation
     * WhatsApp, dans un devis, dans un moteur de recherche — en page
     * introuvable. Un titre se corrige souvent ; une adresse, jamais.</p>
     *
     * <p>La <b>référence</b> ne bouge pas non plus, pour une autre raison :
     * c'est elle qui identifie le produit chez le marchand et sur les
     * bordereaux. La changer désynchroniserait l'entrepôt du catalogue.</p>
     */
    @Transactional
    public DetailProduit modifierProduit(Long produitId, String nom, String description,
                                         Long categorieId, BigDecimal tauxTva, Long modifiePar) {
        Produit produit = produits.findById(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        if (categorieId != null && !categorieId.equals(produit.getCategorie().getId())) {
            produit.setCategorie(categories.findById(categorieId)
                    .orElseThrow(() -> RessourceIntrouvable.de("Catégorie", categorieId)));
        }

        produit.setNom(nom);
        produit.setDescription(description);
        if (tauxTva != null) {
            produit.setTauxTva(tauxTva);
        }
        produit.setModifiePar(modifiePar);

        return DetailProduit.de(produit, urlsMedias::urlPublique);
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
        return DetailProduit.de(produit, urlsMedias::urlPublique);
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

        return resultats.map(p -> ResumeProduit.de(p, urlsMedias::urlPublique));
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

        return DetailProduit.de(produit, urlsMedias::urlPublique);
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

        return DetailProduit.de(produit, urlsMedias::urlPublique);
    }

    private void verifierTransition(Produit produit, StatutProduit vers) {
        if (!TRANSITIONS.get(produit.getStatut()).contains(vers)) {
            throw ConflitEtat.transitionInterdite(
                    "le produit " + produit.getReference(), produit.getStatut().name(), vers.name());
        }
    }
}
