package com.garah.api.serviceclient.domaine;

import java.time.Instant;

/** L'évaluation d'une conversation par le client. */
public record VueEvaluation(Long id, Long conversationId, int note,
                            String commentaire, Instant dateEvaluation) {

    public static VueEvaluation de(EvaluationConversation e) {
        return new VueEvaluation(e.getId(), e.getConversationId(), e.getNote(),
                e.getCommentaire(), e.getDateEvaluation());
    }
}
