package com.garah.api.catalogue.web;

import com.garah.api.catalogue.domaine.ServiceVariante;
import com.garah.api.catalogue.domaine.VueVariante;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Les variantes d'un produit, et leur grille tarifaire.
 *
 * <p>Troisieme maillon du catalogue. L'invariant I-12 exige, pour publier :
 * au moins une variante active, au moins un prix, au moins une photo. Sans
 * ces routes, les deux premiers etaient hors d'atteinte.</p>
 */
@RestController
@RequestMapping("/api/produits/{produitId}/variantes")
public class ControleurVariante {

    private final ServiceVariante variantes;

    public ControleurVariante(ServiceVariante variantes) {
        this.variantes = variantes;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VARIANTE_CONSULTER')")
    public List<VueVariante> lister(@PathVariable Long produitId) {
        return variantes.lister(produitId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('VARIANTE_CREER')")
    public VueVariante ajouter(@PathVariable Long produitId,
                               @Valid @RequestBody DemandeVariante demande) {
        return variantes.ajouter(produitId, demande.libelle());
    }

    /**
     * Cree les declinaisons d'une GRILLE de valeurs.
     *
     * <pre>
     * { "dimensions": [ [12, 13], [45, 46] ] }
     *     Taille 42, 43  ×  Couleur Blanc, Noir  →  quatre declinaisons
     * </pre>
     *
     * <p>C'est le chemin recommande. Le SKU et l'intitule sont COMPOSES a
     * partir des valeurs choisies — donc coherents par construction. La route
     * `POST` simple reste, pour les declinaisons qui ne suivent aucune
     * dimension du referentiel.</p>
     *
     * <p>⚠️ L'ordre des dimensions fixe l'ordre dans le SKU. Envoyer
     * `[couleurs, tailles]` produirait `CH-2026-BLANC-42` la ou
     * `[tailles, couleurs]` produit `CH-2026-42-BLANC`. Les deux sont valides,
     * mais melanger les deux ordres dans un meme catalogue le rend illisible.</p>
     */
    @PostMapping("/grille")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('VARIANTE_CREER')")
    public List<VueVariante> creerGrille(@PathVariable Long produitId,
                                         @Valid @RequestBody DemandeGrille demande) {
        return variantes.creerGrille(produitId, demande.dimensions());
    }

    /**
     * Corrige l'intitule d'une declinaison.
     *
     * <p>⚠️ Le SKU n'est PLUS modifiable : il identifie, et il figure sur des
     * bordereaux et des lignes de commande qui ne se reecrivent pas.</p>
     */
    @PutMapping("/{varianteId}")
    @PreAuthorize("hasAuthority('VARIANTE_MODIFIER')")
    public VueVariante modifier(@PathVariable Long produitId,
                                @PathVariable Long varianteId,
                                @Valid @RequestBody DemandeVariante demande) {
        return variantes.modifier(varianteId, demande.libelle());
    }

    /*
     * Activation et desactivation sur la MEME route, distinguees par le verbe.
     *
     * Deux permissions differentes les gardent (VARIANTE_ACTIVER,
     * VARIANTE_DESACTIVER) : c'est deja le motif retenu pour la publication
     * d'un produit, et le referentiel les avait separees des le depart. Une
     * route unique avec un booleen dans le corps ne pourrait pas les
     * distinguer avant d'avoir lu ce corps — donc apres le controle d'acces.
     */
    @PostMapping("/{varianteId}/activation")
    @PreAuthorize("hasAuthority('VARIANTE_ACTIVER')")
    public VueVariante activer(@PathVariable Long produitId, @PathVariable Long varianteId) {
        return variantes.changerStatut(varianteId, true);
    }

    @DeleteMapping("/{varianteId}/activation")
    @PreAuthorize("hasAuthority('VARIANTE_DESACTIVER')")
    public VueVariante desactiver(@PathVariable Long produitId, @PathVariable Long varianteId) {
        return variantes.changerStatut(varianteId, false);
    }

    /**
     * Change le prix d'un palier existant.
     *
     * <p>Seul le <b>prix</b> se modifie, pas les quantites. Deplacer les
     * bornes d'un palier revient a redecouper la grille : cela peut en faire
     * chevaucher deux autres, ou ouvrir un trou de quantites sans prix. Pour
     * changer un decoupage, on retire le palier et on en pose un autre — deux
     * gestes explicites plutot qu'un seul aux effets invisibles.</p>
     */
    @PutMapping("/{varianteId}/paliers/{palierId}")
    @PreAuthorize("hasAuthority('PRIX_MODIFIER')")
    public VueVariante changerPrix(@PathVariable Long produitId,
                                   @PathVariable Long varianteId,
                                   @PathVariable Long palierId,
                                   @Valid @RequestBody DemandePrix demande) {
        return variantes.changerPrix(varianteId, palierId, demande.prixUnitaire());
    }

    @DeleteMapping("/{varianteId}/paliers/{palierId}")
    @PreAuthorize("hasAuthority('PRIX_SUPPRIMER')")
    public VueVariante supprimerPalier(@PathVariable Long produitId,
                                       @PathVariable Long varianteId,
                                       @PathVariable Long palierId) {
        return variantes.supprimerPalier(varianteId, palierId);
    }

    /**
     * Pose ou remplace un palier de quantite.
     *
     * <p>Le service refuse un chevauchement, et la contrainte d'exclusion de
     * la base est la seconde ligne de defense. Sans elle, deux paliers
     * couvrant « 5 a 10 » donneraient deux prix pour la meme quantite — et
     * c'est le hasard de l'ordre de lecture qui trancherait.</p>
     */
    @PutMapping("/{varianteId}/paliers")
    @PreAuthorize("hasAuthority('PRIX_CREER')")
    public VueVariante definirPalier(@PathVariable Long produitId,
                                     @PathVariable Long varianteId,
                                     @Valid @RequestBody DemandePalier demande) {
        return variantes.definirPalier(varianteId, demande.quantiteMin(),
                demande.quantiteMax(), demande.prixUnitaire());
    }

    /**
     * ⚠️ PLUS DE SKU. Il est engendre a partir de la reference du produit et
     * de l intitule — voir {@code CompositionVariante}.
     *
     * <p>Saisi, il divergeait : « Adidas 42 » portait la reference
     * {@code BL460}, qui ne se rattache a rien. Le commentaire de
     * {@code CompositionVariante} decrivait deja ce defaut pour la grille ;
     * il valait aussi pour ce chemin-ci, par lequel passe le back-office.</p>
     *
     * <p>⚠️ Un client qui envoie encore un champ {@code sku} n obtient pas
     * d erreur : Jackson ignore ce qu il ne connait pas. Le champ est
     * simplement sans effet, ce qui est le bon comportement pendant le
     * decalage entre deux deploiements.</p>
     */
    public record DemandeVariante(
            @NotBlank(message = "Le libelle est obligatoire.")
            @Size(max = 200, message = "Le libelle ne peut pas depasser 200 caracteres.")
            String libelle) {
    }

    public record DemandePalier(
            @NotNull(message = "La quantite minimale est obligatoire.")
            @Min(value = 1, message = "La quantite minimale vaut au moins 1.")
            Integer quantiteMin,

            /*
             * Nul = « et au-dela ». Le dernier palier d'une grille doit rester
             * ouvert : sans lui, une commande de mille unites n'aurait aucun
             * prix, et le panier echouerait au moment de valider.
             */
            @Min(value = 1, message = "La quantite maximale vaut au moins 1.")
            Integer quantiteMax,

            /*
             * TTC, toujours (D-11). Les prix sont stockes taxe comprise et la
             * TVA en est extraite : activer un taux ne fait donc bondir aucun
             * prix affiche.
             */
            @NotNull(message = "Le prix est obligatoire.")
            @DecimalMin(value = "0", message = "Le prix ne peut pas etre negatif.")
            @Digits(integer = 13, fraction = 2, message = "Prix mal forme.")
            BigDecimal prixUnitaire) {
    }

    /** Le nouveau prix d'un palier existant. Les quantites n'y figurent pas. */
    /**
     * Une grille de valeurs : une liste par dimension.
     *
     * <pre>
     * { "dimensions": [ [12, 13], [45, 46] ] }
     * </pre>
     *
     * <p>Une seule dimension est parfaitement valide — un produit qui ne se
     * decline qu'en taille. Deux dimensions donnent le produit cartesien,
     * comme le theme « SizeName-ColorName » d'Amazon.</p>
     */
    public record DemandeGrille(
            @NotNull(message = "La grille est obligatoire.")
            @Size(min = 1, message = "Choisissez au moins une dimension.")
            List<List<Long>> dimensions) {
    }

    public record DemandePrix(
            @NotNull(message = "Le prix est obligatoire.")
            @DecimalMin(value = "0", message = "Le prix ne peut pas etre negatif.")
            @Digits(integer = 13, fraction = 2, message = "Prix mal forme.")
            BigDecimal prixUnitaire) {
    }
}
