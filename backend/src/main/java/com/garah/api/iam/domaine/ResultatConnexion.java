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
        /**
         * La <b>clé d'objet</b> de la photo de profil, ou {@code null}.
         *
         * <p>⚠️ Une clé, pas une URL, et pas non plus un claim du jeton.</p>
         *
         * <p>Pas une URL, parce que la signer demande {@code StockageObjet},
         * qui n'a rien à faire dans le domaine de l'authentification : c'est la
         * couche web qui signe, au moment d'envoyer la réponse.</p>
         *
         * <p>Pas dans le jeton non plus. Un JWT voyage à chaque requête et
         * vit quinze minutes : y mettre une adresse signée valable sept jours
         * la ferait circuler bien au-delà de ce qui est nécessaire, et
         * grossirait chaque appel pour une donnée d'affichage.</p>
         */
        String photoCle,
        Set<String> permissions) {
}
