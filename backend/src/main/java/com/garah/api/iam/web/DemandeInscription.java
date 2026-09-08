package com.garah.api.iam.web;

import com.garah.api.iam.domaine.ServiceInscription;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ce qu'un visiteur envoie pour créer son compte.
 *
 * <p>La validation ici sert à renvoyer un message clair champ par champ. Les
 * vraies règles (unicité de l'adresse, robustesse du mot de passe) sont
 * vérifiées par {@code ServiceInscription} — un DTO ne protège de rien, il
 * met en forme.</p>
 */
public record DemandeInscription(

        @NotBlank(message = "L'adresse e-mail est obligatoire.")
        @Email(message = "Cette adresse e-mail n'est pas valide.")
        @Size(max = 255, message = "Adresse e-mail trop longue.")
        String email,

        /*
         * ⚠️ La longueur vient de ServiceInscription, et AUCUNE règle de
         *    composition ne s'y ajoute.
         *
         * Le message emploie {min} et {max} : ces marqueurs sont remplacés par
         * les valeurs de l'annotation elle-même. Écrire le nombre dans la
         * phrase, c'est promettre qu'on pensera à le changer aux deux endroits
         * le jour venu — et on n'y pense jamais.
         *
         * Exiger « une majuscule, un chiffre, un caractère spécial » produit
         * invariablement Password1! — court, devinable, et présent dans toutes
         * les listes d'attaque. La longueur est ce qui coûte cher à un
         * attaquant ; la complexité imposée ne coûte cher qu'à l'utilisateur.
         *
         * Le maximum à 200 n'est pas cosmétique : BCrypt tronque au-delà de
         * 72 octets, et accepter un mot de passe d'un mégaoctet, c'est offrir
         * un déni de service — chaque tentative coûterait 250 ms de CPU.
         */
        @NotBlank(message = "Le mot de passe est obligatoire.")
        @Size(min = ServiceInscription.LONGUEUR_MOT_DE_PASSE_MIN, max = 200,
              message = "Le mot de passe doit contenir entre {min} et {max} caractères.")
        String motDePasse,

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 100, message = "Le nom ne peut pas dépasser 100 caractères.")
        String nom,

        @Size(max = 100, message = "Le prénom ne peut pas dépasser 100 caractères.")
        String prenom,

        /*
         * Format permissif volontairement : +237 6 99 00 00 00, 0699000000,
         * (237) 699-000-000 sont tous écrits par de vraies personnes. Un
         * format trop strict fait échouer une inscription pour un espace.
         */
        @Size(max = 30, message = "Numéro de téléphone trop long.")
        @Pattern(regexp = "^$|^[+()0-9 .-]{6,30}$",
                 message = "Ce numéro de téléphone n'est pas valide.")
        String telephone,

        @Pattern(regexp = "^$|^(fr|en|sg)$",
                 message = "La langue doit être fr, en ou sg.")
        String langue) {
}
