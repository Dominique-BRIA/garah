package com.garah.api.serviceclient.infra;

import com.garah.api.serviceclient.domaine.PropositionPrix;
import com.garah.api.serviceclient.domaine.StatutProposition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PropositionPrixRepository extends JpaRepository<PropositionPrix, Long> {

    List<PropositionPrix> findByConversationIdOrderByDateCreationAsc(Long conversationId);

    /**
     * Les propositions a marquer expirees.
     *
     * <p>Alimente le travail periodique. Sans lui, une proposition PROPOSEE
     * resterait acceptable indefiniment : un client pourrait accepter en
     * octobre un prix propose en mars.</p>
     */
    List<PropositionPrix> findByStatutAndDateExpirationBefore(StatutProposition statut,
                                                              Instant limite);
}
