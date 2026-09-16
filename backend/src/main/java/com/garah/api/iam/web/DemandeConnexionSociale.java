package com.garah.api.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ce que le client envoie pour « Continuer avec… ».
 *
 * <p>⚠️ <b>Remarque ce qui n'y figure PAS : ni adresse e-mail, ni nom, ni
 * identifiant.</b> Tout cela est lu dans le jeton, après vérification de sa
 * signature.</p>
 *
 * <p>Accepter un champ {@code email} ici serait la faille : il suffirait de
 * poster l'adresse de quelqu'un d'autre pour ouvrir sa session. Le jeton est
 * la <b>seule</b> chose que le client transmet, parce que c'est la seule qu'il
 * ne peut pas fabriquer.</p>
 */
public record DemandeConnexionSociale(

        @NotBlank(message = "Le fournisseur est obligatoire.")
        @Pattern(regexp = "GOOGLE|FACEBOOK|TIKTOK",
                message = "Fournisseur inconnu.")
        String fournisseur,

        /*
         * La borne haute n'est pas décorative : un jeton Google fait environ
         * 1 Ko. Sans elle, on accepterait un corps de plusieurs mégaoctets
         * qu'il faudrait analyser avant de le rejeter.
         */
        @NotBlank(message = "Le jeton est obligatoire.")
        @Size(max = 8192, message = "Jeton trop long.")
        String jeton) {
}
