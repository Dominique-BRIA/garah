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

    /**
     * Les prix acceptés pour CE client, CET article et CETTE quantité.
     *
     * <p>⚠️ La quantité doit être EXACTEMENT celle négociée. Un prix accordé
     * pour dix unités n'est pas un prix pour trois — ni pour cinquante.</p>
     *
     * <p>Le client se lit par la conversation : une proposition n'appartient
     * qu'à celui avec qui elle a été négociée.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT p FROM PropositionPrix p, Conversation c
             WHERE c.id = p.conversationId
               AND c.clientId = :clientId
               AND p.varianteId = :varianteId
               AND p.quantite = :quantite
               AND p.statut = com.garah.api.serviceclient.domaine.StatutProposition.ACCEPTEE
               AND p.dateExpiration > :maintenant
             ORDER BY p.dateCreation DESC
            """)
    java.util.List<PropositionPrix> utilisablesPour(
            @org.springframework.data.repository.query.Param("clientId") Long clientId,
            @org.springframework.data.repository.query.Param("varianteId") Long varianteId,
            @org.springframework.data.repository.query.Param("quantite") int quantite,
            @org.springframework.data.repository.query.Param("maintenant") java.time.Instant maintenant);
}
