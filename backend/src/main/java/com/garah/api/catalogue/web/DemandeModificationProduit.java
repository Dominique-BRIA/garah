package com.garah.api.catalogue.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Ce qu'un responsable envoie pour corriger une fiche produit.
 *
 * <p>⚠️ Ni la <b>référence</b> ni le <b>slug</b> n'y figurent, et c'est
 * délibéré. La référence identifie le produit chez le marchand et sur les
 * bordereaux ; le slug est son adresse publique. Les rendre modifiables dans
 * le même formulaire que le nom laisserait croire qu'ils se corrigent aussi
 * facilement — alors que l'un désynchronise l'entrepôt et l'autre casse tous
 * les liens déjà partagés.</p>
 */
public record DemandeModificationProduit(

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 200, message = "Le nom ne peut pas dépasser 200 caractères.")
        String nom,

        @Size(max = 5000, message = "La description ne peut pas dépasser 5000 caractères.")
        String description,

        @NotNull(message = "La catégorie est obligatoire.")
        Long categorieId,

        /*
         * Nul = « ne change pas le taux ». Un écran qui n'affiche pas la TVA
         * ne doit pas la remettre à zéro en enregistrant le reste.
         */
        @DecimalMin(value = "0.0", message = "Le taux de TVA ne peut pas être négatif.")
        @DecimalMax(value = "100.0", message = "Le taux de TVA ne peut pas dépasser 100.")
        BigDecimal tauxTva) {
}
