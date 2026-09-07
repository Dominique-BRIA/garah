package com.garah.api.commerce.domaine;

import com.garah.api.iam.domaine.NomClient;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Une commande telle qu'une liste l'affiche.
 *
 * <p>Volontairement sans ses lignes. Renvoyer le détail complet de chaque
 * commande d'une page de vingt-cinq multiplierait le poids de la réponse par
 * dix, pour des lignes que personne ne lit avant d'avoir cliqué.</p>
 *
 * <p>Le client y figure en revanche, avec son <b>code</b> et son <b>nom</b> :
 * une liste de commandes qui n'afficherait qu'un identifiant numérique
 * obligerait à ouvrir chaque fiche pour savoir de qui elle vient.</p>
 *
 * @param clientNom {@code null} si le client a été supprimé de la base — la
 *                  commande, elle, ne disparaît jamais
 */
public record ResumeCommande(
        Long id,
        String numero,
        String statut,
        Long clientId,
        String clientCode,
        String clientNom,
        int nombreLignes,
        BigDecimal montantTotal,
        String devise,
        Instant dateCreation) {

    public static ResumeCommande de(Commande c, NomClient client) {
        return new ResumeCommande(
                c.getId(), c.getNumero(), c.getStatut().name(),
                c.getClientId(),
                client == null ? null : client.code(),
                client == null ? null : client.nom(),
                c.getLignes().size(),
                c.getMontantTotal(), c.getDevise(), c.getDateCreation());
    }
}
