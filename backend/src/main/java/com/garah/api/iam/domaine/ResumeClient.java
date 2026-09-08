package com.garah.api.iam.domaine;

import java.time.Instant;

/**
 * Un client tel qu'une liste l'affiche.
 *
 * <h2>Ce qu'elle ne contient pas, et pourquoi</h2>
 *
 * <p>⚠️ <b>Ni mot de passe haché, ni jeton, ni adresse.</b> C'est une
 * projection construite en SQL, pas une entité convertie : le domaine appelant
 * ne peut donc pas exposer par inadvertance un champ qu'on ne voulait pas
 * montrer. Le jour où {@code Utilisateur} gagne une colonne sensible, cette
 * vue ne la reçoit pas.</p>
 *
 * <p>{@code emailVerifie} y figure en revanche, et c'est utile : un compte
 * dont l'adresse n'est pas confirmée ne reçoit aucun courriel — ni le lien de
 * suivi, ni la confirmation de commande. « Pourquoi ce client dit ne rien
 * recevoir ? » se répond ici, pas dans les journaux du serveur.</p>
 */
public record ResumeClient(
        Long id,
        String code,
        String nom,
        String email,
        String telephone,
        boolean emailVerifie,
        String statut,
        Instant dateInscription) {
}
