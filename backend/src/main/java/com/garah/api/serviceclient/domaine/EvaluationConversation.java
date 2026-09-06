package com.garah.api.serviceclient.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * La note laissee par le client apres cloture.
 *
 * <p>Une seule par conversation ({@code UNIQUE}), et seulement si la
 * conversation est CLOSED : evaluer un echange en cours n aurait pas de sens,
 * et fausserait la mesure de satisfaction de la §20.</p>
 */
@Entity
@Table(name = "evaluation_conversation")
public class EvaluationConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false)
    private int note;

    @Column(columnDefinition = "text")
    private String commentaire;

    @Column(name = "date_evaluation", nullable = false, updatable = false)
    private Instant dateEvaluation = Instant.now();

    protected EvaluationConversation() {
    }

    public EvaluationConversation(Long conversationId, int note, String commentaire) {
        this.conversationId = conversationId;
        this.note = note;
        this.commentaire = commentaire;
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public int getNote() { return note; }
    public String getCommentaire() { return commentaire; }
    public Instant getDateEvaluation() { return dateEvaluation; }
}
