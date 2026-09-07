package com.garah.api.iam.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Changer son mot de passe.
 *
 * <p>⚠️ <b>Le mot de passe actuel est exigé, alors que l'appelant est déjà
 * authentifié.</b> Ce n'est pas une redondance : un poste laissé déverrouillé,
 * un jeton volé, et le compte serait pris définitivement en une requête. Le
 * redemander est ce qui garantit que celui qui change le mot de passe est bien
 * celui qui le connaît.</p>
 *
 * <p>La longueur minimale n'est <b>pas</b> déclarée ici. Elle vit dans le
 * domaine, à côté de celle de l'inscription : une règle de sécurité écrite à
 * deux endroits finit par diverger, et c'est toujours la copie oubliée qui
 * reste la plus permissive.</p>
 */
public record DemandeChangementMotDePasse(

        @NotBlank(message = "Le mot de passe actuel est obligatoire.")
        String actuel,

        @NotBlank(message = "Le nouveau mot de passe est obligatoire.")
        String nouveau) {
}
