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

    /** Le NOMBRE, sans charger les lignes : pour le tableau de bord. */
    long countByStatut(StatutConversation statut);

    Page<Conversation> findByClientIdOrderByDateCreationDesc(Long clientId, Pageable pagination);

    Page<Conversation> findByPrisParAndStatut(Long prisPar,
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
               AND (:prisPar IS NULL OR c.prisPar = :prisPar)
             ORDER BY c.dateCreation ASC
            """)
    Page<Conversation> administration(@Param("statut") StatutConversation statut,
                                      @Param("prisPar") Long prisPar,
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
     *
     * <p>⚠️ LES NON LUS NE COMPTENT QUE LES MESSAGES DU CLIENT. Le
     * {@code lu} d'un message est faux a l'ecriture, quel qu'en soit
     * l'auteur : sans le test sur l'expediteur, la reponse que l'agent vient
     * de taper se comptait comme un non-lu de plus. On envoyait un message et
     * le compteur montait — 2 non lus devenaient 3.</p>
     *
     * <p>Un message vient du client quand son expediteur EST le client de la
     * conversation. Les deux pointent vers {@code utilisateur}, la racine
     * commune : la comparaison est directe, sans colonne supplementaire.</p>
     */
    @Query("""
            SELECT m.conversation.id, count(m),
                   sum(CASE WHEN m.lu = false
                                 AND m.expediteurId = m.conversation.clientId
                            THEN 1 ELSE 0 END),
                   max(m.dateEnvoi)
              FROM Message m
             WHERE m.conversation.id IN :ids
             GROUP BY m.conversation.id
            """)
    List<Object[]> totauxPar(@Param("ids") Collection<Long> ids);

    /**
     * Les messages de CLIENTS jamais ouverts, toutes conversations confondues.
     *
     * <p>🎯 Pour la pastille du menu « Service client ». Sans elle, on n'ouvre
     * l'écran que si l'on y pense — et un client qui attend depuis trois jours
     * attend parce que personne n'a eu l'idée de regarder.</p>
     *
     * <p>⚠️ Le compte est GLOBAL, pas « les miennes » : la file d'attente
     * n'appartient à personne, et c'est justement ce qui n'y est affecté à
     * personne qui risque le plus de rester sans réponse.</p>
     *
     * <p>⚠️ Les conversations CLOSES sont exclues. Un message arrivé juste
     * avant la fermeture resterait non lu pour toujours, et la pastille
     * afficherait un nombre que rien ne peut faire descendre.</p>
     */
    @Query("""
            SELECT count(m) FROM Message m
             WHERE m.lu = false
               AND m.expediteurId = m.conversation.clientId
               AND m.conversation.statut <> com.garah.api.serviceclient.domaine.StatutConversation.CLOSED
            """)
    long totalNonLus();

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
               SET c.prisPar = :prisPar,
                   c.statut = com.garah.api.serviceclient.domaine.StatutConversation.ASSIGNED,
                   c.dateAffectation = CURRENT_TIMESTAMP
             WHERE c.id = :conversationId
               AND c.statut = com.garah.api.serviceclient.domaine.StatutConversation.WAITING
            """)
    int prendre(@Param("conversationId") Long conversationId,
                @Param("prisPar") Long prisPar);
}
