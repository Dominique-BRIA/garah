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
        return variantes.ajouter(produitId, demande.sku(), demande.libelle());
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

    public record DemandeVariante(
            @NotBlank(message = "Le SKU est obligatoire.")
            @Size(max = 50, message = "Le SKU ne peut pas depasser 50 caracteres.")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                     message = "Le SKU ne peut contenir que lettres, chiffres, points, tirets et underscores.")
            String sku,

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
}
