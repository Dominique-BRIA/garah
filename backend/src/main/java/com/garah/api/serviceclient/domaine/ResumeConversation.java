package com.garah.api.serviceclient.domaine;

import com.garah.api.iam.domaine.NomClient;

import java.time.Instant;

/**
 * Une conversation telle qu'une liste l'affiche.
 *
 * <h2>Sans ses messages, et avec ce qu'il faut pour trier</h2>
 *
 * <p>Une page de vingt-cinq conversations ne transporte pas leurs quatre cents
 * messages : personne ne les lit avant d'avoir cliqué. Mais une liste qui
 * n'afficherait que des sujets ne répondrait pas à la seule question que se
 * pose un agent en l'ouvrant — <b>« laquelle attend ma réponse ? »</b></p>
 *
 * @param nonLus            le nombre de messages jamais ouverts. C'est
 *                          <b>lui</b> qui répond à la question ci-dessus ; le
 *                          total des messages ne dit rien d'utile tout seul.
 * @param dernierMessageLe  {@code null} si personne n'a encore écrit. Une
 *                          conversation peut exister sans message : le sujet
 *                          suffit à l'ouvrir.
 * @param clientNom         {@code null} si le compte a disparu. La
 *                          conversation reste : elle porte ce qui a été dit.
 */
public record ResumeConversation(
        Long id,
        Long clientId,
        String clientCode,
        String clientNom,
        Long responsableId,
        String sujet,
        String statut,
        long nombreMessages,
        long nonLus,
        Instant dernierMessageLe,
        Instant dateCreation,
        Instant dateAffectation,
        Instant dateCloture) {

    public static ResumeConversation de(Conversation c, NomClient client,
                                        long nombreMessages, long nonLus,
                                        Instant dernierMessageLe) {
        return new ResumeConversation(
                c.getId(), c.getClientId(),
                client == null ? null : client.code(),
                client == null ? null : client.nom(),
                c.getResponsableId(), c.getSujet(), c.getStatut().name(),
                nombreMessages, nonLus, dernierMessageLe,
                c.getDateCreation(), c.getDateAffectation(), c.getDateCloture());
    }
}
