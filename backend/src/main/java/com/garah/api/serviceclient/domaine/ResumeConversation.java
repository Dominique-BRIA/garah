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
        Long prisPar,
        /** ⚠️ Le NOM, pas l'identifiant : « 7 » ne dit à personne qui a pris. */
        String prisParNom,
        Long closPar,
        String closParNom,
        String sujet,
        String statut,
        long nombreMessages,
        long nonLus,
        Instant dernierMessageLe,
        Instant dateCreation,
        Instant dateAffectation,
        Instant dateCloture,
        // Vrai pour l'Assistance GARAH : le back-office n'offre pas de la clore.
        boolean assistance) {

    public static ResumeConversation de(Conversation c, NomClient client,
                                        long nombreMessages, long nonLus,
                                        Instant dernierMessageLe) {
        return de(c, client, null, null, nombreMessages, nonLus, dernierMessageLe);
    }

    public static ResumeConversation de(Conversation c, NomClient client,
                                        String prisParNom, String closParNom,
                                        long nombreMessages, long nonLus,
                                        Instant dernierMessageLe) {
        return new ResumeConversation(
                c.getId(), c.getClientId(),
                client == null ? null : client.code(),
                client == null ? null : client.nom(),
                c.getPrisPar(), prisParNom, c.getClosPar(), closParNom,
                c.getSujet(), c.getStatut().name(),
                nombreMessages, nonLus, dernierMessageLe,
                c.getDateCreation(), c.getDateAffectation(), c.getDateCloture(), c.estAssistance());
    }
}
