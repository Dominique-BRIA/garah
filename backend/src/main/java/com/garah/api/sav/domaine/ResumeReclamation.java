package com.garah.api.sav.domaine;

import com.garah.api.iam.domaine.NomClient;

import java.time.Instant;

/**
 * Une réclamation telle qu'une liste l'affiche.
 *
 * <h2>Pourquoi la description n'y est pas</h2>
 *
 * <p>Elle peut faire deux mille caractères. Une page de vingt-cinq
 * réclamations en transporterait cinquante mille, dont personne ne lit une
 * ligne avant d'avoir cliqué. Le <b>motif</b>, lui, tient sur un mot et suffit
 * à trier.</p>
 *
 * <h2>Pourquoi le client y est</h2>
 *
 * <p>Une liste de réclamations qui n'afficherait qu'un identifiant numérique
 * obligerait à ouvrir chaque fiche pour savoir de qui elle vient — alors que
 * « qui se plaint, et de quoi ? » est justement la question qu'on se pose en
 * ouvrant l'écran.</p>
 *
 * @param clientNom     {@code null} si le client a disparu de la base. La
 *                      réclamation, elle, reste : elle décrit un mécontentement
 *                      qui a bien été exprimé.
 * @param commandeNumero {@code null} si la réclamation ne porte sur aucune
 *                       commande — c'est permis : on peut se plaindre du
 *                       service sans avoir rien acheté.
 */
public record ResumeReclamation(
        Long id,
        String numero,
        String statut,
        String motif,
        Long clientId,
        String clientCode,
        String clientNom,
        Long commandeId,
        String commandeNumero,
        Long responsableId,
        Instant dateCreation,
        Instant dateResolution) {

    public static ResumeReclamation de(Reclamation r, NomClient client, String commandeNumero) {
        return new ResumeReclamation(
                r.getId(), r.getNumero(), r.getStatut().name(), r.getMotif(),
                r.getClientId(),
                client == null ? null : client.code(),
                client == null ? null : client.nom(),
                r.getCommandeId(), commandeNumero,
                r.getResponsableId(),
                r.getDateCreation(), r.getDateResolution());
    }
}
