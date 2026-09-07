package com.garah.api.serviceclient.infra;

import com.garah.api.serviceclient.domaine.Conversation;
import com.garah.api.serviceclient.domaine.StatutConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * La liste du back-office.
     *
     * <p>Deux filtres, et ils repondent a deux questions differentes : le
     * statut a « qu est-ce qui attend d etre pris ? », le responsable a
     * « qu est-ce que MOI je traite ? ». Les fondre en un seul obligerait un
     * agent a parcourir les conversations de toute l equipe pour retrouver les
     * siennes.</p>
     *
     * <p>Les plus ANCIENNES d abord : une conversation qui traine est un
     * client qui attend. C est le meme choix que les reclamations, et
     * l inverse des listes de catalogue.</p>
     */
    @Query("""
            SELECT c FROM Conversation c
             WHERE (:statut IS NULL OR c.statut = :statut)
               AND (:responsableId IS NULL OR c.responsableId = :responsableId)
             ORDER BY c.dateCreation ASC
            """)
    Page<Conversation> administration(@Param("statut") StatutConversation statut,
                                      @Param("responsableId") Long responsableId,
                                      Pageable pagination);

    /**
     * Combien de messages, combien de non lus, et le dernier ecrit quand —
     * pour toute la page, en UNE requete.
     *
     * <p>Compter conversation par conversation ferait vingt-six requetes pour
     * vingt-cinq lignes. C est la regle du projet : une requete par page,
     * jamais une par ligne.</p>
     *
     * <p>Le {@code sum(case ...)} plutot qu un second {@code count} filtre :
     * on veut les deux chiffres dans le MEME balayage, pas deux passes sur la
     * meme table.</p>
     */
    @Query("""
            SELECT m.conversation.id, count(m), sum(CASE WHEN m.lu THEN 0 ELSE 1 END),
                   max(m.dateEnvoi)
              FROM Message m
             WHERE m.conversation.id IN :ids
             GROUP BY m.conversation.id
            """)
    List<Object[]> totauxPar(@Param("ids") Collection<Long> ids);

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
