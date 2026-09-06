package com.garah.api.serviceclient.infra;

import com.garah.api.serviceclient.domaine.EvaluationConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EvaluationConversationRepository
        extends JpaRepository<EvaluationConversation, Long> {

    Optional<EvaluationConversation> findByConversationId(Long conversationId);
}
