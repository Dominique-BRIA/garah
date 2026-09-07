package com.garah.api.iam.domaine;

import java.time.Instant;

/**
 * Son propre compte, tel qu'on le montre à son propriétaire.
 *
 * <h2>Pourquoi ceci n'est pas l'entité {@link Utilisateur}</h2>
 *
 * <p>L'entité porte l'empreinte du mot de passe. Exposer l'entité, c'est
 * accepter qu'un jour un ajout de champ la fasse sortir dans une réponse JSON
 * — et une empreinte BCrypt publiée est un mot de passe à changer pour tout le
 * monde. Le test d'architecture interdit d'ailleurs qu'une entité JPA traverse
 * la couche web.</p>
 *
 * <h2>Ce qui n'est PAS ici, et pourquoi</h2>
 *
 * <ul>
 *   <li><b>Le statut du compte.</b> Un compte bloqué ne consulte pas son
 *       profil : il ne se connecte pas. L'afficher ne renseignerait que
 *       quelqu'un qui n'a pas besoin de l'information.</li>
 *   <li><b>Les permissions.</b> Elles sont déjà dans le jeton et servies par
 *       {@code GET /api/auth/moi}. Les redonner ici en ferait deux sources
 *       pour une même vérité.</li>
 * </ul>
 *
 * @param emailVerifie faux tant que le lien de confirmation n'a pas été
 *                     ouvert (D-23) — l'interface doit le montrer, sans quoi
 *                     personne ne comprend pourquoi commander est refusé
 */
public record ProfilUtilisateur(
        Long id,
        String nom,
        String prenom,
        String email,
        String telephone,
        String langue,
        String type,
        boolean emailVerifie,
        Instant dateCreation,
        Instant dateDerniereConnexion) {

    static ProfilUtilisateur de(Utilisateur utilisateur) {
        return new ProfilUtilisateur(
                utilisateur.getId(),
                utilisateur.getNom(),
                utilisateur.getPrenom(),
                utilisateur.getEmail(),
                utilisateur.getTelephone(),
                utilisateur.getLangue(),
                utilisateur.getType().name(),
                utilisateur.estEmailVerifie(),
                utilisateur.getDateCreation(),
                utilisateur.getDateDerniereConnexion());
    }
}
