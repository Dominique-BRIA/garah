package com.garah.api.catalogue.web;

import com.garah.api.catalogue.domaine.DetailProduit;
import com.garah.api.catalogue.domaine.ResumeProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.domaine.StatutProduit;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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

    /** Le catalogue public. Aucune authentification : la vitrine est ouverte. */
    @GetMapping
    public Page<ResumeProduit> catalogue(
            @RequestParam(required = false) Long categorieId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int taille) {

        return catalogue.catalogue(categorieId, PageRequest.of(
                Math.max(page, 0),
                Math.clamp(taille, 1, TAILLE_MAX),
                Sort.by("nom")));
    }

    @GetMapping("/{slug}")
    public DetailProduit fichePublique(@PathVariable String slug) {
        return catalogue.fichePublique(slug);
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
}
