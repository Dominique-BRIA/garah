package com.garah.api.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Le numéro et le code reçu.
 *
 * <p>⚠️ Le {@code @Pattern} n'est pas une protection — le service vérifie
 * l'empreinte, seul contrôle qui compte. Il évite qu'une saisie manifestement
 * fautive (« abc », un code collé avec des espaces) consomme une des cinq
 * tentatives et rapproche la personne du blocage pour une faute de frappe.</p>
 */
public record DemandeConnexionWhatsApp(

        @NotBlank(message = "Le numéro est obligatoire.")
        @Size(max = 30, message = "Numéro trop long.")
        String telephone,

        @NotBlank(message = "Le code est obligatoire.")
        @Pattern(regexp = "\\d{6}", message = "Le code comporte six chiffres.")
        String code) {
}
