package com.garah.api.catalogue.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Ce qu'un responsable envoie pour créer une fiche produit. */
public record DemandeCreationProduit(

        @NotNull(message = "Le marchand est obligatoire.")
        Long marchandId,

        @NotNull(message = "La catégorie est obligatoire.")
        Long categorieId,

        @NotBlank(message = "La référence est obligatoire.")
        @Size(max = 50, message = "La référence ne peut pas dépasser 50 caractères.")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                 message = "La référence ne peut contenir que des lettres, chiffres, points, tirets et underscores.")
        String reference,

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 200, message = "Le nom ne peut pas dépasser 200 caractères.")
        String nom) {
}
