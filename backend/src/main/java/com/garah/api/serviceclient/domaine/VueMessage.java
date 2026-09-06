package com.garah.api.serviceclient.domaine;

import java.time.Instant;

/**
 * Un message d'une conversation.
 *
 * <p>{@code conversationId} est passé en paramètre plutôt que lu via
 * {@code message.getConversation()} : cette relation est paresseuse, et la
 * suivre hors transaction échouerait.</p>
 */
public record VueMessage(
        Long id,
        Long conversationId,
        Long expediteurId,
        String contenu,
        boolean lu,
        Instant dateEnvoi) {

    public static VueMessage de(Message m) {
        return new VueMessage(m.getId(), null, m.getExpediteurId(),
                m.getContenu(), m.estLu(), m.getDateEnvoi());
    }

    public static VueMessage de(Message m, Long conversationId) {
        return new VueMessage(m.getId(), conversationId, m.getExpediteurId(),
                m.getContenu(), m.estLu(), m.getDateEnvoi());
    }
}
