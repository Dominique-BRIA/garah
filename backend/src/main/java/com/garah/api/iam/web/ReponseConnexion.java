package com.garah.api.iam.web;

import com.garah.api.iam.domaine.ResultatConnexion;

import java.util.Set;

/**
 * Ce que l'API renvoie après une connexion réussie.
 *
 * <p>Aucune donnée sensible : ni empreinte de mot de passe, ni téléphone.
 * Ce record est le <b>contrat</b> de l'API — il ne changera que si on décide
 * de le changer, indépendamment de l'évolution des entités.</p>
 */
public record ReponseConnexion(
        String jeton,
        String typeJeton,
        long expireDansSecondes,
        Utilisateur utilisateur,
        Set<String> permissions) {

    /** Le strict nécessaire pour afficher l'en-tête de l'application. */
    public record Utilisateur(Long id, String nom, String type, String langue) {
    }

    public static ReponseConnexion de(ResultatConnexion resultat) {
        return new ReponseConnexion(
                resultat.jeton(),
                "Bearer",
                resultat.dureeSecondes(),
                new Utilisateur(
                        resultat.utilisateurId(),
                        resultat.nom(),
                        resultat.type().name(),
                        resultat.langue()),
                resultat.permissions());
    }
}
