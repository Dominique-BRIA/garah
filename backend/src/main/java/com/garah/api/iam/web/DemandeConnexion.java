package com.garah.api.iam.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ce que le client envoie pour se connecter.
 *
 * <p>C'est un <b>DTO</b>, pas une entité : le contrat de l'API est séparé du
 * schéma de la base. Renommer une colonne ne doit pas casser les trois
 * frontends (chapitre 06, piège 5).</p>
 *
 * <p>La validation ici ne protège de rien — elle sert à renvoyer un message
 * clair plutôt qu'une erreur obscure. La vraie vérification est faite par le
 * service.</p>
 */
public record DemandeConnexion(

        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "Cette adresse e-mail n'est pas valide.")
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Size(max = 200, message = "Mot de passe trop long.")
        String motDePasse) {
}
