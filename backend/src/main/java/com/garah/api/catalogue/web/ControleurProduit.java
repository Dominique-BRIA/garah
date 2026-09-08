package com.garah.api.catalogue.web;

import com.garah.api.catalogue.domaine.DetailProduit;
import com.garah.api.catalogue.domaine.FicheVitrine;
import com.garah.api.catalogue.domaine.ResumeProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.domaine.StatutProduit;
import com.garah.api.catalogue.domaine.VueCorbeille;
import com.garah.api.commun.erreur.ErreurMetier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Les routes du catalogue.
 *
 * <p>Remarque ce que ce contrôleur ne fait <b>pas</b> : il n'importe aucune
 * entité JPA, il n'ouvre aucune transaction, il ne contient aucune règle
 * métier. Il traduit du HTTP en appels de service, et c'est tout.</p>
 */
@RestController
@RequestMapping("/api/produits")
public class ControleurProduit {

    /**
     * Plafond de taille de page.
     *
     * <p>Sans lui, {@code ?taille=1000000} charge tout le catalogue en mémoire.
     * Ce n'est pas une hypothèse : c'est la première chose qu'un robot
     * d'indexation essaie.</p>
     */
    private static final int TAILLE_MAX = 100;

    private final ServiceCatalogue catalogue;

    public ControleurProduit(ServiceCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * Le catalogue public. Aucune authentification : la vitrine est ouverte.
     *
     * <p>La recherche porte sur le nom, le <b>vendeur</b> et la
     * <b>catégorie</b> — ce qu'un client a en tête. Pas sur la référence
     * interne, qu'il n'a jamais vue.</p>
     *
     * <p>🎯 Elle est faite par la <b>base</b>. Laisser la vitrine filtrer la
     * page reçue donnerait une recherche qui ne trouve pas ce qui est en page
     * deux — et le visiteur en conclurait que l'article n'existe pas.</p>
     */
    @GetMapping
    public Page<ResumeProduit> catalogue(
            @RequestParam(required = false) Long categorieId,
            @RequestParam(required = false) String recherche,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int taille) {

        return catalogue.catalogue(categorieId, recherche, PageRequest.of(
                Math.max(page, 0),
                Math.clamp(taille, 1, TAILLE_MAX),
                Sort.by("nom")));
    }

    @GetMapping("/{slug}")
    public DetailProduit fichePublique(@PathVariable String slug) {
        return catalogue.fichePublique(slug);
    }

    /**
     * La fiche telle que la <b>vitrine</b> l'affiche. <b>Route publique.</b>
     *
     * <p>🎯 Distincte de {@link #fichePublique}, et pas par confort :
     * « deux publics, deux routes ». Celle-ci porte la <b>grille de prix</b> et
     * la <b>disponibilité par déclinaison</b> — sans quoi le prix change entre
     * la fiche et le panier, ce qui ressemble à une arnaque.</p>
     *
     * <p>Elle coûte trois requêtes de plus. Les fondre imposerait ce coût à la
     * fiche d'administration, qui n'affiche ni l'une ni l'autre.</p>
     *
     * <p>⚠️ Les paliers rendus ici sont ceux <b>du jour</b>. Le tarif qui
     * compte est figé au passage de commande (D-10) : cette route informe,
     * elle n'engage pas.</p>
     */
    @GetMapping("/{slug}/vitrine")
    public FicheVitrine ficheVitrine(@PathVariable String slug) {
        return catalogue.ficheVitrine(slug);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUIT_CREER')")
    public DetailProduit creer(@Valid @RequestBody DemandeCreationProduit demande,
                               @AuthenticationPrincipal Jwt jeton) {
        return catalogue.creerProduit(
                demande.marchandId(), demande.categorieId(), demande.nom(),
                Long.valueOf(jeton.getSubject()));
    }

    /**
     * Corrige une fiche produit.
     *
     * <p>Un {@code PUT} et non un {@code PATCH} : le formulaire du back-office
     * envoie la fiche entière, telle qu'elle est à l'écran. Un {@code PATCH}
     * demanderait de distinguer « champ absent » de « champ vidé », et cette
     * distinction est exactement l'endroit où une description finit par être
     * effacée sans que personne ne l'ait demandé.</p>
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUIT_MODIFIER')")
    public DetailProduit modifier(@PathVariable Long id,
                                  @Valid @RequestBody DemandeModificationProduit demande,
                                  @AuthenticationPrincipal Jwt jeton) {
        return catalogue.modifierProduit(id, demande.nom(), demande.description(),
                demande.categorieId(), demande.tauxTva(), Long.valueOf(jeton.getSubject()));
    }

    /**
     * La permission est écrite avec le code exact de {@code cas_utilisation}.
     *
     * <p>Aucune traduction entre la base, le jeton et cette annotation :
     * une traduction, c'est un endroit de plus où se tromper (chapitre 08 §7).</p>
     */
    @PostMapping("/{id}/publication")
    @PreAuthorize("hasAuthority('PRODUIT_PUBLIER')")
    public DetailProduit publier(@PathVariable Long id) {
        return catalogue.publier(id);
    }

    @DeleteMapping("/{id}/publication")
    @PreAuthorize("hasAuthority('PRODUIT_DEPUBLIER')")
    public DetailProduit depublier(@PathVariable Long id) {
        return catalogue.changerStatut(id, StatutProduit.MASQUE);
    }

    @PostMapping("/{id}/archivage")
    @PreAuthorize("hasAuthority('PRODUIT_ARCHIVER')")
    public DetailProduit archiver(@PathVariable Long id) {
        return catalogue.changerStatut(id, StatutProduit.ARCHIVE);
    }

    // -------------------------------------------------------------------------
    // Suppression
    // -------------------------------------------------------------------------

    /**
     * Met un brouillon à la corbeille. <b>Rien n'est effacé ici.</b>
     *
     * <p>Le produit disparaît de toutes les listes mais reste entier :
     * déclinaisons, prix, photos. Il se restaure d'un clic, et ne s'efface
     * qu'en vidant la corbeille.</p>
     *
     * <p>Refusé par un {@code 409} si le produit n'est <b>pas</b> un
     * brouillon : un produit publié a pu être vu, mis au panier, négocié —
     * son chemin est l'archivage, qui préserve les commandes qui le citent.</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUIT_SUPPRIMER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        catalogue.mettreALaCorbeille(id);
    }

    /**
     * Supprime plusieurs produits d'un coup.
     *
     * <h2>🎯 Réussite PARTIELLE, jamais tout ou rien</h2>
     *
     * <p>Sur dix produits cochés, deux peuvent avoir été vendus. Refuser les
     * dix obligerait à décocher à l'aveugle pour trouver lesquels ; tout
     * supprimer serait pire. On supprime donc les huit et on <b>nomme</b> les
     * deux qui restent, avec la raison.</p>
     *
     * <p>Chaque suppression a sa propre transaction — c'est le proxy Spring
     * qui l'ouvre, puisque l'appel part d'ici et non de l'intérieur du
     * service. Sans cela, le premier refus annulerait les précédentes.</p>
     *
     * <p>Répond toujours {@code 200}, jamais {@code 409} : la requête a bien
     * été traitée, et son résultat est dans le corps. Un code d'erreur global
     * ferait croire que rien n'a été fait alors que huit produits sont
     * partis.</p>
     */
    @DeleteMapping
    @PreAuthorize("hasAuthority('PRODUIT_SUPPRIMER')")
    public ResultatSuppression supprimerPlusieurs(@Valid @RequestBody DemandeSuppression demande) {
        List<Long> supprimes = new ArrayList<>();
        List<Refus> refuses = new ArrayList<>();

        for (Long id : demande.ids()) {
            try {
                catalogue.mettreALaCorbeille(id);
                supprimes.add(id);
            } catch (ErreurMetier e) {
                refuses.add(new Refus(id, e.getCode(), e.getMessage()));
            }
        }

        return new ResultatSuppression(supprimes, refuses);
    }

    /** Les identifiants à supprimer. */
    public record DemandeSuppression(
            @NotEmpty(message = "Aucun produit sélectionné.")
            @Size(max = 100, message = "Cent produits au maximum par suppression.")
            List<Long> ids) {
    }

    /** Ce qui est parti, et ce qui est resté — avec la raison. */
    public record ResultatSuppression(List<Long> supprimes, List<Refus> refuses) {
    }

    public record Refus(Long id, String code, String message) {
    }

    // -------------------------------------------------------------------------
    // La corbeille
    // -------------------------------------------------------------------------

    /**
     * Ce qui attend dans la corbeille, du plus récemment jeté au plus ancien.
     *
     * <p>Gardée par {@code PRODUIT_SUPPRIMER} et non {@code PRODUIT_CONSULTER} :
     * ce n'est pas une vue du catalogue, c'est l'antichambre de l'effacement.
     * Qui n'a pas le droit de jeter n'a pas de raison de voir ce qui a été
     * jeté.</p>
     */
    /**
     * Le nombre de produits, et rien d'autre.
     *
     * <p>Pour le tableau de bord, qui n'affiche qu'un chiffre. Passer par
     * {@code /administration?taille=1} coûtait <b>quatre</b> allers-retours
     * vers la base — la page, les marchands, les prix, les disponibilités —
     * dont on ne gardait que {@code totalElements}.</p>
     *
     * <p>⚠️ Déclarée AVANT {@code /administration/{id}} n'est pas nécessaire
     * ici — Spring préfère toujours un chemin littéral à une variable — mais
     * le jour où quelqu'un renomme cette route, le piège redevient celui de
     * {@code produits/nouveau} côté Angular. Le rappel vaut mieux que la
     * surprise.</p>
     */
    @GetMapping("/administration/nombre")
    @PreAuthorize("hasAuthority('PRODUIT_CONSULTER')")
    public long nombre() {
        return catalogue.nombreProduits();
    }

    @GetMapping("/corbeille")
    @PreAuthorize("hasAuthority('PRODUIT_SUPPRIMER')")
    public Page<VueCorbeille> corbeille(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "24") int taille) {
        return catalogue.corbeille(PageRequest.of(
                Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)));
    }

    /**
     * Sort un produit de la corbeille.
     *
     * <p>Il retrouve son statut d'avant, intact — un brouillon revient
     * brouillon.</p>
     */
    @PostMapping("/corbeille/{id}/restauration")
    @PreAuthorize("hasAuthority('PRODUIT_SUPPRIMER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restaurer(@PathVariable Long id) {
        catalogue.restaurerProduit(id);
    }

    /**
     * Efface pour de bon. <b>Il n'y a pas de retour après celle-ci.</b>
     *
     * <p>⚠️ La route porte {@code /corbeille/} dans son chemin, et ce n'est pas
     * cosmétique : on ne peut effacer définitivement que ce qui est <b>déjà</b>
     * dans la corbeille. Un identifiant erroné ne peut donc pas détruire un
     * produit en vente — la requête ne le trouverait pas.</p>
     */
    @DeleteMapping("/corbeille/{id}")
    @PreAuthorize("hasAuthority('PRODUIT_SUPPRIMER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void viderUn(@PathVariable Long id) {
        // Les fichiers sont retirés APRÈS la transaction : le service rend les
        // clés, le contrôleur déclenche le nettoyage.
        catalogue.supprimerFichiers(catalogue.viderDeLaCorbeille(id));
    }

    /**
     * La fiche complète, brouillons compris — réservée au back-office.
     *
     * <p>Deux routes distinctes pour le même objet, avec deux règles d'accès
     * différentes. C'est volontaire : mélanger les deux dans un seul endpoint
     * conditionné par un {@code if (estResponsable)} est exactement le genre
     * de code où une fuite finit par se glisser.</p>
     */
    @GetMapping("/administration/{id}")
    @PreAuthorize("hasAuthority('PRODUIT_CONSULTER')")
    public DetailProduit ficheAdministration(@PathVariable Long id) {
        return catalogue.ficheAdministration(id);
    }

    /**
     * La liste du back-office : <b>tous les statuts</b>.
     *
     * <p>Le {@code GET /api/produits} juste au-dessus est la vitrine, et il ne
     * montre que les produits publiés. Faire servir les deux besoins par une
     * seule route conditionnée par « est-ce que l'appelant a le droit de voir
     * les brouillons ? » est exactement le genre d'endroit où une fuite finit
     * par se glisser — un brouillon, un prix non validé, un produit retiré de
     * la vente s'afficheraient un jour chez un client.</p>
     */
    @GetMapping("/administration")
    @PreAuthorize("hasAuthority('PRODUIT_CONSULTER')")
    public Page<ResumeProduit> listeAdministration(
            @RequestParam(required = false) String recherche,
            /*
             * TOUS, EN_STOCK, FAIBLE ou RUPTURE.
             *
             * Une valeur inconnue retombe sur TOUS plutôt que de répondre 400 :
             * un paramètre d'URL bricolé à la main ne doit pas donner
             * l'impression que l'écran est cassé.
             */
            @RequestParam(required = false) String disponibilite,
            /*
             * BROUILLON, PUBLIE, MASQUE, ARCHIVE — ou rien pour tous.
             *
             * Filtre en BASE, jamais sur la page reçue : trier après coup
             * donnerait « 3 sur 24 » ici et « 7 sur 24 » à la page suivante,
             * avec un total qui ne voudrait rien dire.
             */
            @RequestParam(required = false) String statut,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int taille) {

        return catalogue.administration(recherche, statut, disponibilite, PageRequest.of(
                Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX),
                Sort.by(Sort.Direction.DESC, "dateCreation")));
    }
}
