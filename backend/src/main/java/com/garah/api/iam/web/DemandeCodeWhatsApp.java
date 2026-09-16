package com.garah.api.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Le numéro auquel envoyer le code. */
public record DemandeCodeWhatsApp(

        @NotBlank(message = "Le numéro est obligatoire.")
        @Size(max = 30, message = "Numéro trop long.")
        String telephone) {
}
