package com.garah.api.serviceclient.infra;

import com.garah.api.serviceclient.domaine.AffectationConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AffectationConversationRepository
        extends JpaRepository<AffectationConversation, Long> {

    Optional<AffectationConversation> findByConversationIdAndDateFinIsNull(Long conversationId);

    List<AffectationConversation> findByConversationIdOrderByDateDebutAsc(Long conversationId);
}
