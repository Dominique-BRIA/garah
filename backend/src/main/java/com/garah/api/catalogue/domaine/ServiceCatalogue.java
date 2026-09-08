package com.garah.api.catalogue.domaine;

import com.garah.api.catalogue.infra.*;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.commun.stockage.DepotFichiers;
import com.garah.api.commun.stockage.StockageObjet;
import com.garah.api.marchand.domaine.ServiceMarchand;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    /** Le referentiel des dimensions, pour composer les declinaisons d une grille. */
    private final ValeurAttributRepository valeursAttribut;

    /**
     * Traduit les clés d'objet en adresses affichables.
     *
     * <p>Depuis D-21, le catalogue ne peut plus se contenter de renvoyer des
     * clés : avec un bucket privé, seul le serveur peut fabriquer une URL
     * lisible, puisqu'il faut la signer.</p>
     */
    private final StockageObjet urlsMedias;

    /**
     * Pour effacer les fichiers d'un produit supprimé.
     *
     * <p>Distinct de {@link #urlsMedias} : celui-ci <b>lit</b> (il signe des
     * adresses), celui-là <b>écrit</b> (il dépose et efface). Les confondre
     * donnerait au catalogue le droit d'effacer partout où il ne fait que
     * lire.</p>
     */
    private final DepotFichiers fichiers;

    /**
     * Le marchand, en lecture seule, pour engendrer la référence d'un produit.
     *
     * <p>Le catalogue dépend du marchand, jamais l'inverse : le test ArchUnit
     * « aucun cycle entre les domaines » le vérifie au build.</p>
     */
    private final ServiceMarchand marchands;

    /**
     * Le canal par lequel le catalogue ANNONCE, sans commander.
     *
     * <p>Il n appelle pas le stock : celui-ci depend deja du catalogue pour
     * designer ce qu il compte, et l appel inverse formerait un cycle.</p>
     */
    private final ApplicationEventPublisher evenements;

    public ServiceCatalogue(ProduitRepository produits,
                            VarianteRepository variantes,
                            CategorieProduitRepository categories,
                            MediaRepository medias,
                            TarificationRepository tarifications,
                            StockageObjet urlsMedias,
                            DepotFichiers fichiers,
                            ServiceMarchand marchands,
                            ApplicationEventPublisher evenements,
                            ValeurAttributRepository valeursAttribut) {
        this.produits = produits;
        this.variantes = variantes;
        this.categories = categories;
        this.medias = medias;
        this.tarifications = tarifications;
        this.urlsMedias = urlsMedias;
        this.fichiers = fichiers;
        this.marchands = marchands;
        this.evenements = evenements;
        this.valeursAttribut = valeursAttribut;
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
        annoncerLaNaissance(produit.ajouterVariante(reference, nom, true));

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
        annoncerLaNaissance(variante);
        return variante;
    }

    /**
     * Crée les déclinaisons d'une <b>grille</b> de valeurs.
     *
     * <pre>
     * Taille  42, 43        →  42 — Blanc     43 — Blanc
     * Couleur Blanc, Noir      42 — Noir      43 — Noir
     * </pre>
     *
     * <h2>Pourquoi une grille plutôt qu'une par une</h2>
     *
     * <p>Quatre déclinaisons créées séparément, ce sont quatre occasions
     * d'écrire un intitulé différemment. La grille les produit toutes d'un
     * geste, avec des SKU et des intitulés <b>composés</b> — donc cohérents
     * par construction.</p>
     *
     * <h2>Ce que la méthode fait taire, délibérément</h2>
     *
     * <p>Une combinaison qui existe déjà est <b>ignorée</b>, pas refusée.
     * Ajouter la couleur « Rouge » à un produit qui a déjà 42-Blanc et
     * 43-Blanc doit créer 42-Rouge et 43-Rouge sans se plaindre des deux
     * autres. Refuser toute la grille pour un doublon obligerait à décocher à
     * l'aveugle ce qui existe déjà.</p>
     *
     * @param valeursParAttribut une liste de valeurs par dimension. L'ordre
     *                           des dimensions fixe l'ordre dans le SKU.
     */
    @Transactional
    public List<Variante> creerGrille(Long produitId, List<List<Long>> valeursParAttribut) {
        Produit produit = produits.findById(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        List<List<ValeurAttribut>> dimensions = valeursParAttribut.stream()
                .map(this::chargerValeurs)
                .filter(liste -> !liste.isEmpty())
                .toList();

        if (dimensions.isEmpty()) {
            throw new RegleMetierViolee("GRILLE_VIDE",
                    "Choisissez au moins une valeur pour créer des déclinaisons.");
        }

        // Les combinaisons déjà présentes, pour les sauter sans rien dire.
        Set<String> existantes = produit.getVariantes().stream()
                .map(ServiceCatalogue::empreinte)
                .collect(Collectors.toSet());

        List<Variante> creees = new ArrayList<>();

        for (List<ValeurAttribut> combinaison : produitCartesien(dimensions)) {
            String sku = CompositionVariante.sku(produit.getReference(), combinaison);

            if (existantes.contains(empreinteDe(combinaison)) || variantes.existsBySku(sku)) {
                continue;
            }

            Variante variante = produit.ajouterVariante(
                    sku, CompositionVariante.libelle(produit.getNom(), combinaison), false);
            combinaison.forEach(variante::definirPar);
            annoncerLaNaissance(variante);
            creees.add(variante);
        }

        if (creees.isEmpty()) {
            throw new ConflitEtat("GRILLE_DEJA_CREEE",
                    "Toutes ces combinaisons existent déjà.");
        }
        return creees;
    }

    /**
     * Les valeurs d'une dimension, dans l'ordre du référentiel.
     *
     * <p>⚠️ Toutes doivent appartenir au <b>même</b> attribut. Mélanger une
     * taille et une couleur dans la même dimension produirait une grille où
     * « 42 » et « Blanc » s'excluent, alors qu'ils se combinent.</p>
     */
    private List<ValeurAttribut> chargerValeurs(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<ValeurAttribut> valeurs = ids.stream()
                .distinct()
                .map(id -> valeursAttribut.findById(id)
                        .orElseThrow(() -> RessourceIntrouvable.de("Valeur d'attribut", id)))
                .sorted(Comparator.comparingInt(ValeurAttribut::getOrdre))
                .toList();

        long nbAttributs = valeurs.stream()
                .map(v -> v.getAttribut().getId())
                .distinct()
                .count();

        if (nbAttributs > 1) {
            throw new RegleMetierViolee("DIMENSION_MELANGEE",
                    "Une dimension de la grille ne peut porter que les valeurs "
                    + "d'un seul attribut.");
        }
        return valeurs;
    }

    /**
     * Le produit cartésien des dimensions.
     *
     * <p>{@code [[42, 43], [Blanc, Noir]]} donne quatre combinaisons. C'est
     * exactement ce qu'Amazon appelle une variation à deux thèmes.</p>
     */
    private static List<List<ValeurAttribut>> produitCartesien(List<List<ValeurAttribut>> dimensions) {
        List<List<ValeurAttribut>> resultat = new ArrayList<>();
        resultat.add(new ArrayList<>());

        for (List<ValeurAttribut> dimension : dimensions) {
            List<List<ValeurAttribut>> etendu = new ArrayList<>();
            for (List<ValeurAttribut> debut : resultat) {
                for (ValeurAttribut valeur : dimension) {
                    List<ValeurAttribut> suite = new ArrayList<>(debut);
                    suite.add(valeur);
                    etendu.add(suite);
                }
            }
            resultat = etendu;
        }
        return resultat;
    }

    /** Les identifiants de valeurs, triés et joints : deux fois les mêmes = doublon. */
    private static String empreinte(Variante variante) {
        return variante.getValeurs().stream()
                .map(v -> String.valueOf(v.getId()))
                .sorted()
                .collect(Collectors.joining("-"));
    }

    private static String empreinteDe(List<ValeurAttribut> valeurs) {
        return valeurs.stream()
                .map(v -> String.valueOf(v.getId()))
                .sorted()
                .collect(Collectors.joining("-"));
    }

    /**
     * Annonce qu'une déclinaison est née, pour que son stock naisse avec elle.
     *
     * <p>Le {@code saveAndFlush} n'est pas décoratif : tant que la déclinaison
     * n'est pas écrite, elle n'a <b>pas d'identifiant</b>, et l'événement ne
     * désignerait rien. La cascade depuis le produit l'aurait écrite plus tard,
     * à la fin de la transaction — trop tard pour l'écouteur.</p>
     */
    private void annoncerLaNaissance(Variante variante) {
        variantes.saveAndFlush(variante);
        evenements.publishEvent(new VarianteCreee(variante.getId()));
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
    // Suppression
    // -------------------------------------------------------------------------

    /**
     * Supprime définitivement un produit — <b>uniquement s'il n'a jamais servi</b>.
     *
     * <h2>Supprimer et archiver ne sont pas deux façons de dire la même chose</h2>
     *
     * <p>La règle était écrite dans le référentiel depuis V14 :
     * {@code PRODUIT_SUPPRIMER}, « Supprimer un produit jamais vendu ». Elle
     * n'avait simplement jamais été implémentée.</p>
     *
     * <pre>
     * jamais commandé, jamais mis au panier, jamais négocié   → on SUPPRIME
     * a servi ne serait-ce qu'une fois                        → on ARCHIVE
     * </pre>
     *
     * <p>🎯 <b>Pourquoi on ne peut pas supprimer un produit vendu.</b> Une
     * ligne de commande pointe sur la variante. L'effacer viderait des
     * commandes passées de leur objet : un client verrait une facture avec un
     * article devenu introuvable, et le grand livre marchand perdrait la
     * contrepartie de sommes déjà encaissées. L'archivage existe exactement
     * pour ça — le produit sort du catalogue, l'historique reste entier.</p>
     *
     * <p>Les tables qui bloquent sont précisément celles qui référencent
     * {@code variante} <b>sans</b> {@code ON DELETE CASCADE}. Le reste —
     * variantes, médias, tarifications, stock — s'efface avec le produit,
     * parce que rien de tout cela n'a de sens sans lui.</p>
     *
     * @return les clés des fichiers à retirer du stockage, à la charge de
     *         l'appelant : voir {@link #supprimerFichiers}
     */
    @Transactional
    public void mettreALaCorbeille(Long produitId) {
        Produit produit = produits.findById(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

        // 🎯 SEUL UN BROUILLON PART A LA CORBEILLE.
        //
        // Un produit publié, même retiré de la vitrine, a pu être vu, mis au
        // panier, négocié. Le chemin qui lui correspond est l'ARCHIVAGE : il
        // sort du catalogue et l'historique reste entier. Ouvrir la corbeille
        // aux produits publiés reviendrait à proposer deux gestes pour la même
        // chose, dont un seul est correct.
        if (produit.getStatut() != StatutProduit.BROUILLON) {
            throw new ConflitEtat("PRODUIT_PAS_BROUILLON",
                    "« " + produit.getNom() + " » n'est plus un brouillon : il a été "
                    + "publié au moins une fois. Archivez-le pour le retirer du "
                    + "catalogue — l'archivage préserve les commandes qui le citent.");
        }

        produit.mettreALaCorbeille();
        produits.save(produit);
    }

    /** Les produits en corbeille, du plus récemment jeté au plus ancien. */
    @Transactional(readOnly = true)
    public Page<VueCorbeille> corbeille(Pageable pagination) {
        return produits.corbeille(pagination).map(l -> new VueCorbeille(
                l.getId(), l.getReference(), l.getNom(),
                l.getStatut(), l.getCategorieNom(), l.getDateSuppression()));
    }

    /**
     * Sort un produit de la corbeille.
     *
     * <p>Il retrouve son statut d'avant, intact : un brouillon revient
     * brouillon. C'est tout l'intérêt d'avoir gardé la corbeille séparée du
     * statut.</p>
     */
    @Transactional
    public void restaurerProduit(Long produitId) {
        if (produits.restaurer(produitId) == 0) {
            throw RessourceIntrouvable.de("Produit en corbeille", produitId);
        }
    }

    /**
     * Vide un produit de la corbeille — <b>définitivement</b>.
     *
     * <p>🎯 <b>Le contrôle « a déjà servi » reste posé ici, et il n'est pas
     * redondant.</b> Un brouillon n'a en principe jamais été vendu — il n'a
     * jamais été publié. Mais c'est un raisonnement sur la machine à états,
     * pas une garantie : un import, une reprise de données, une transition
     * ajoutée un jour suffiraient à le prendre en défaut. Ce qui coûte une
     * requête protège ici des commandes qui perdraient leur objet.</p>
     *
     * @return les clés des fichiers à retirer du stockage, à la charge de
     *         l'appelant : voir {@link #supprimerFichiers}
     */
    @Transactional
    public List<String> viderDeLaCorbeille(Long produitId) {
        String nom = produits.nomDansCorbeille(produitId)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit en corbeille", produitId));

        if (variantes.aDejaServi(produitId)) {
            throw new ConflitEtat("PRODUIT_DEJA_VENDU",
                    "« " + nom + " » a déjà été commandé, mis au panier ou négocié : "
                    + "l'effacer viderait de leur objet des commandes passées. "
                    + "Restaurez-le puis archivez-le.");
        }

        // Les clés sont relevées AVANT l'effacement : après, la ligne n'existe
        // plus et les fichiers resteraient sur le stockage sans que rien ne
        // permette de les retrouver.
        List<String> cles = produits.clesMediasDe(produitId);

        produits.supprimerDefinitivement(produitId);
        return cles;
    }

    /**
     * Retire du stockage les fichiers d'un produit supprimé.
     *
     * <p>⚠️ Appelé <b>hors</b> de la transaction, et sans jamais lever. La
     * ligne est déjà effacée : échouer ici rendrait une erreur à quelqu'un
     * dont la suppression a parfaitement réussi. On laisse quelques objets
     * orphelins sur le stockage plutôt que de mentir sur le résultat.</p>
     */
    public void supprimerFichiers(List<String> cles) {
        for (String cle : cles) {
            try {
                fichiers.supprimer(cle);
            } catch (RuntimeException e) {
                // Rien à faire de plus : le produit est parti, le fichier
                // n'est plus référencé, et il ne coûte que son octetage.
                journalDeSuppression(cle, e);
            }
        }
    }

    private void journalDeSuppression(String cle, RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(ServiceCatalogue.class)
                .warn("Fichier {} non supprime du stockage : {}",
                        cle, e.getClass().getSimpleName());
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

    /**
     * Le catalogue public : uniquement les produits publiés.
     *
     * <p>La recherche porte sur le nom, le <b>vendeur</b> et la
     * <b>catégorie</b> — ce qu'un client a en tête. Elle ignore la référence
     * interne, qu'il n'a jamais vue.</p>
     *
     * <p>🎯 <b>Elle est faite par la base, pas par le navigateur.</b> Filtrer
     * côté client ne porterait que sur la page reçue : un article de la page
     * suivante ne remonterait jamais, et le visiteur conclurait qu'il n'existe
     * pas. C'est le genre de rustine qui tient tant que le catalogue est
     * petit, et qui ment dès qu'il grandit.</p>
     */
    @Transactional(readOnly = true)
    public Page<ResumeProduit> catalogue(Long categorieId, String recherche, Pageable pagination) {
        String filtre = (recherche == null || recherche.isBlank()) ? null : recherche.strip();

        return enrichir(produits.vitrine(StatutProduit.PUBLIE, categorieId, filtre, pagination));
    }

    /**
     * Les vignettes de produits dont on connaît déjà les identifiants.
     *
     * <h2>🎯 Elle sert deux écrans qui ont le même besoin</h2>
     *
     * <p>Les <b>tendances</b> rendent un classement d'identifiants, les
     * <b>favoris</b> une liste d'identifiants. Ni l'un ni l'autre ne porte de
     * photo ni de prix : ce sont des agrégats, pas du catalogue. Sans cette
     * méthode, chaque écran demanderait une fiche par ligne — exactement ce
     * qu'on s'interdit.</p>
     *
     * <h2>⚠️ L'ordre demandé est l'ordre rendu</h2>
     *
     * <p>Pour les tendances, <b>l'ordre EST le classement</b> : le rendre dans
     * l'ordre de la base transformerait un palmarès en liste alphabétique,
     * sans que rien ne le signale.</p>
     *
     * <p>Un identifiant inconnu ou dépublié disparaît simplement de la
     * réponse. C'est voulu : un produit retiré de la vente ne doit pas
     * réapparaître dans une liste de favoris, et une place vide dans un
     * classement vaut mieux qu'un article qu'on ne peut plus acheter.</p>
     */
    @Transactional(readOnly = true)
    public List<ResumeProduit> parIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        Map<Long, ResumeProduit> parId = enrichir(
                produits.vitrineParIds(StatutProduit.PUBLIE, Set.copyOf(ids)))
                .stream()
                .collect(Collectors.toMap(ResumeProduit::id, r -> r));

        return ids.stream().map(parId::get).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * La liste du back-office : tous les statuts, brouillons compris.
     *
     * <p>Distincte du catalogue public, et pas par excès de prudence : une
     * liste de gestion qui ne montrerait que les produits publiés cacherait
     * exactement ceux sur lesquels il reste du travail.</p>
     */
    @Transactional(readOnly = true)
    public Page<ResumeProduit> administration(String recherche, String statut,
                                              String disponibilite, Pageable pagination) {
        String filtre = (recherche == null || recherche.isBlank()) ? null : recherche.strip();

        // Un statut inconnu vaut « tous » plutôt qu'une erreur : un paramètre
        // d'URL bricolé à la main ne doit pas donner l'impression d'un écran
        // cassé. Même règle que la disponibilité.
        StatutProduit filtreStatut = statutValide(statut);

        // Une valeur inconnue ne fait pas échouer la requête : elle retombe sur
        // « tous ». Un back-office qui répond 400 parce qu'un paramètre d'URL a
        // été bricolé à la main donne l'impression d'être cassé.
        String mode = Disponibilite.valide(disponibilite);

        return enrichir(produits.administration(filtre, filtreStatut, mode, pagination));
    }

    /**
     * Combien de produits au catalogue — <b>rien d'autre</b>.
     *
     * <h2>🎯 Pourquoi une méthode pour un seul nombre</h2>
     *
     * <p>Le tableau de bord lisait ce chiffre par
     * {@code /api/produits/administration?taille=1}, puis ne gardait que
     * {@code totalElements}. Or cette route ENRICHIT la page qu'elle rend :
     * elle résout le marchand, le prix d'appel et la disponibilité.</p>
     *
     * <pre>
     * pour afficher UN nombre :  4 allers-retours vers Neon
     *   1. la page de produits            (+ sa requête de comptage)
     *   2. les noms de marchands
     *   3. les prix d'appel
     *   4. les quantités disponibles      ← que j'ai ajoutée sans y penser
     * </pre>
     *
     * <p>Trois de ces quatre requêtes portaient sur <b>un seul produit</b>,
     * dont on jetait ensuite toutes les données. Ici : un {@code count(*)}.</p>
     *
     * <p>Le coût ne se voit pas en local — quatre requêtes sur une base à
     * 2 ms, c'est 8 ms. Il se voit depuis Douala, sur six cartes chargées en
     * même temps par une instance à un seul cœur.</p>
     *
     * <p>La corbeille est exclue sans qu'on ait à le dire :
     * {@code @SQLRestriction} s'applique aussi à {@code count()}.</p>
     */
    @Transactional(readOnly = true)
    public long nombreProduits() {
        return produits.count();
    }

    /** {@code null} = tous les statuts, y compris pour une valeur inconnue. */
    private static StatutProduit statutValide(String demande) {
        if (demande == null || demande.isBlank() || "TOUS".equalsIgnoreCase(demande.strip())) {
            return null;
        }
        try {
            return StatutProduit.valueOf(demande.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException inconnu) {
            return null;
        }
    }

    /** Les filtres de disponibilité proposés par la liste d'administration. */
    public enum Disponibilite {
        TOUS, EN_STOCK, FAIBLE, RUPTURE;

        static String valide(String demande) {
            if (demande == null || demande.isBlank()) {
                return TOUS.name();
            }
            try {
                return valueOf(demande.strip().toUpperCase(java.util.Locale.ROOT)).name();
            } catch (IllegalArgumentException inconnu) {
                return TOUS.name();
            }
        }
    }

    /**
     * Complète une page de produits avec le marchand et le prix d'appel.
     *
     * <p>🎯 <b>Deux requêtes pour toute la page, pas deux par ligne.</b> La
     * version naturelle — lire le marchand et la grille dans le {@code map} —
     * en ferait quarante-huit pour vingt-quatre produits. Ce coût est
     * invisible en développement, avec trois produits et une base locale ; il
     * ne l'est plus sur une base distante et une connexion mobile.</p>
     */
    private Page<ResumeProduit> enrichir(Page<Produit> page) {
        return new PageImpl<>(enrichir(page.getContent()), page.getPageable(),
                page.getTotalElements());
    }

    /**
     * Le même enrichissement, sur une liste.
     *
     * <p>C'est ici que vit la logique ; la variante paginée ne fait que la
     * rhabiller. Les écrire deux fois ferait diverger la liste et la page le
     * jour où l'une gagne une colonne — et ce jour-là, seule l'une des deux
     * afficherait le prix.</p>
     */
    private List<ResumeProduit> enrichir(List<Produit> trouves) {
        if (trouves.isEmpty()) {
            return List.of();
        }

        Set<Long> idsMarchands = trouves.stream()
                .map(Produit::getMarchandId)
                .collect(Collectors.toSet());

        List<Long> idsProduits = trouves.stream()
                .map(Produit::getId)
                .toList();

        Map<Long, String> nomsMarchands = marchands.nomsPar(idsMarchands);

        Map<Long, PrixMinProduit> prix = tarifications
                .prixMinPar(idsProduits, LocalDate.now()).stream()
                // Un produit n'a qu'une devise en pratique. Si ce n'était plus
                // vrai, on garde le prix le plus bas : c'est celui qui est
                // annoncé, et annoncer le plus élevé serait mentir à la baisse
                // dans l'autre sens.
                .collect(Collectors.toMap(PrixMinProduit::produitId, p -> p,
                        (a, b) -> a.prixMin().compareTo(b.prixMin()) <= 0 ? a : b));

        // La disponibilité, agrégée par produit — une requête pour la page.
        Map<Long, Integer> disponibles = variantes.disponibilitesPar(idsProduits).stream()
                .collect(Collectors.toMap(
                        VarianteRepository.DisponibiliteProduit::getProduitId,
                        VarianteRepository.DisponibiliteProduit::getDisponible));

        return trouves.stream()
                .map(p -> ResumeProduit.de(p, urlsMedias::urlPublique,
                        nomsMarchands.get(p.getMarchandId()), prix.get(p.getId()),
                        // Absent de la table : le produit n'a aucune ligne de
                        // stock, ce qui vaut zéro et non « inconnu ».
                        disponibles.getOrDefault(p.getId(), 0)))
                .toList();
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
     * La fiche telle que la <b>vitrine</b> l'affiche : prix et disponibilité
     * compris.
     *
     * <h2>Ce que {@link #fichePublique} ne savait pas dire</h2>
     *
     * <p>{@code DetailProduit} ne porte <b>aucun prix</b> : ni tarif, ni palier,
     * ni stock par déclinaison. Une vitrine ne peut donc pas afficher la grille
     * « 1–6 : 5 000 · 7 et + : 3 000 » — et sans elle, le prix change entre la
     * fiche et le panier, ce qui ressemble à une arnaque.</p>
     *
     * <h2>Quatre requêtes, quel que soit le nombre de déclinaisons</h2>
     *
     * <pre>
     * 1. le produit, ses déclinaisons et ses médias
     * 2. toutes les grilles de prix   d'un coup
     * 3. toutes les disponibilités    d'un coup
     * 4. le nom du marchand
     * </pre>
     *
     * <p>Une chemise en cinq tailles et trois couleurs fait quinze
     * déclinaisons. Appeler {@code grille()} et {@code stock.etat()} par
     * déclinaison ferait trente et une requêtes pour une seule page — c'est la
     * règle du projet, et elle ne se voit qu'en production.</p>
     *
     * <p>⚠️ Les déclinaisons <b>inactives</b> sont retirées : la vitrine ne
     * montre que ce qui se vend. Les garder afficherait des tailles qu'on ne
     * peut pas commander, et le client conclurait à une panne.</p>
     */
    @Transactional(readOnly = true)
    public FicheVitrine ficheVitrine(String slug) {
        Produit produit = produits.findBySlug(slug)
                .filter(Produit::estPublie)
                .orElseThrow(() -> RessourceIntrouvable.de("Produit", slug));

        // `estActive()` et non une comparaison de chaîne : le statut vaut
        // « ACTIVE » ici et « ACTIF » ailleurs dans le projet, et l'écrire à la
        // main donne une liste vide sans la moindre erreur.
        List<Variante> actives = produit.getVariantes().stream()
                .filter(Variante::estActive)
                .toList();

        List<Long> ids = actives.stream().map(Variante::getId).toList();

        Map<Long, List<PalierPrix>> grilles = ids.isEmpty() ? Map.of()
                : tarifications.paliersEnVigueurPour(ids, LocalDate.now()).stream()
                        .collect(Collectors.groupingBy(
                                t -> t.getVariante().getId(),
                                Collectors.mapping(PalierPrix::de, Collectors.toList())));

        Map<Long, Integer> disponibles = ids.isEmpty() ? Map.of()
                : variantes.disponibilitesParVariante(ids).stream()
                        .collect(Collectors.toMap(
                                VarianteRepository.DisponibiliteVariante::getVarianteId,
                                VarianteRepository.DisponibiliteVariante::getDisponible));

        List<FicheVitrine.Declinaison> declinaisons = actives.stream()
                .map(v -> {
                    List<PalierPrix> paliers = grilles.getOrDefault(v.getId(), List.of());
                    return new FicheVitrine.Declinaison(
                            v.getId(), v.getSku(), v.getLibelle(), v.estParDefaut(),
                            paliers,
                            disponibles.getOrDefault(v.getId(), 0),
                            // Le MOQ : le plus petit palier de la grille. Zéro
                            // quand il n'y a aucun tarif — la déclinaison n'est
                            // alors pas achetable, et l'écran le dira.
                            paliers.isEmpty() ? 0 : paliers.getFirst().quantiteMin());
                })
                .toList();

        return new FicheVitrine(
                produit.getId(), produit.getNom(), produit.getSlug(), produit.getDescription(),
                produit.getTauxTva(),
                produit.getMarchandId(),
                // Nul si le marchand a disparu : la fiche reste lisible sans
                // lui, et l'écran écrit « vendeur inconnu » plutôt que rien.
                marchands.nomsPar(List.of(produit.getMarchandId()))
                        .get(produit.getMarchandId()),
                new FicheVitrine.Categorie(produit.getCategorie().getId(),
                        produit.getCategorie().getNom(), produit.getCategorie().getSlug()),
                declinaisons,
                produit.getMedias().stream()
                        .map(m -> new FicheVitrine.Media(m.getId(), m.getType().name(),
                                urlsMedias.urlPublique(m.getCleObjet()),
                                m.estPrincipal(), m.getOrdre()))
                        .toList());
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
