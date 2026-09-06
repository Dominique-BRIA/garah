package com.garah.api.iam.domaine;

import java.util.Set;

/**
 * Ce que l'application obtient après une connexion réussie.
 *
 * <p>Les permissions sont renvoyées <b>en plus</b> d'être dans le jeton :
 * le frontend en a besoin pour masquer les boutons interdits. C'est du
 * confort, jamais de la sécurité — l'autorisation reste vérifiée côté serveur
 * à chaque appel (chapitre 04 §3).</p>
 */
public record ResultatConnexion(
        String jeton,
        long dureeSecondes,
        Long utilisateurId,
        TypeUtilisateur type,
        String nom,
        String langue,
        Set<String> permissions) {
}
