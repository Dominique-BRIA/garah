package com.garah.api.iam.domaine;

import java.time.Instant;

/**
 * Un client, tel que le back-office l'ouvre.
 *
 * <h2>Plus riche que {@link ResumeClient}, et pas de beaucoup</h2>
 *
 * <p>La fiche ajoute la langue, la date de dernière connexion et l'état de la
 * double authentification — ce qu'il faut pour répondre au téléphone. Elle
 * n'ajoute <b>ni</b> mot de passe, <b>ni</b> jeton, <b>ni</b> historique de
 * connexion : ces trois-là ne servent à rien pour aider un client, et tout
 * pour usurper son compte.</p>
 *
 * <p>🎯 {@code dateDerniereConnexion} nulle veut dire « ne s'est jamais
 * connecté ». C'est une information, pas un trou : un compte créé et jamais
 * utilisé explique à lui seul la moitié des « je n'ai rien reçu ».</p>
 */
public record FicheClient(
        Long id,
        String code,
        Long utilisateurId,
        String nom,
        String prenom,
        String email,
        String telephone,
        boolean emailVerifie,
        boolean deuxFacteursActif,
        String langue,
        String statut,
        Instant dateInscription,
        Instant dateDerniereConnexion) {

    public static FicheClient de(Client c) {
        Utilisateur u = c.getUtilisateur();
        return new FicheClient(
                c.getId(), c.getCodeClient(), u.getId(),
                u.getNom(), u.getPrenom(), u.getEmail(), u.getTelephone(),
                u.estEmailVerifie(), u.isDeuxFacteursActif(), u.getLangue(),
                c.getStatut().name(), c.getDateInscription(),
                u.getDateDerniereConnexion());
    }
}
