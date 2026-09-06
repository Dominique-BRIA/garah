package com.garah.api.serviceclient.infra;

import com.garah.api.serviceclient.domaine.Conversation;
import com.garah.api.serviceclient.domaine.StatutConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByStatutOrderByDateCreationAsc(StatutConversation statut);

    Page<Conversation> findByClientIdOrderByDateCreationDesc(Long clientId, Pageable pagination);

    Page<Conversation> findByResponsableIdAndStatut(Long responsableId,
                                                    StatutConversation statut,
                                                    Pageable pagination);

    @Query("""
            SELECT DISTINCT c FROM Conversation c
              LEFT JOIN FETCH c.messages
             WHERE c.id = :id
            """)
    Optional<Conversation> chargerAvecMessages(Long id);

    /**
     * Prend une conversation en attente — de facon atomique.
     *
     * <p>C est la SECONDE technique du chapitre 05 : au lieu de verrouiller
     * puis verifier, on demande a la base de faire l operation et on regarde
     * combien de lignes ont bouge.</p>
     *
     * <pre>
     * 1 ligne modifiee  →  j ai eu la conversation
     * 0 ligne modifiee  →  quelqu un a ete plus rapide
     * </pre>
     *
     * <p>Pas de verrou, pas d attente, une seule requete. C est le bon outil
     * ici parce qu on n a besoin d aucune valeur AVANT — contrairement au
     * stock, ou il fallait journaliser quantite_avant.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Conversation c
               SET c.responsableId = :responsableId,
                   c.statut = com.garah.api.serviceclient.domaine.StatutConversation.ASSIGNED,
                   c.dateAffectation = CURRENT_TIMESTAMP
             WHERE c.id = :conversationId
               AND c.statut = com.garah.api.serviceclient.domaine.StatutConversation.WAITING
            """)
    int prendre(@Param("conversationId") Long conversationId,
                @Param("responsableId") Long responsableId);
}
