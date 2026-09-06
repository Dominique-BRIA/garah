package com.garah.api.serviceclient.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * L historique des prises et des retraits d une conversation.
 *
 * <p>La conversation elle-meme ne porte que son responsable ACTUEL. Cette
 * table garde la trace de tous ceux qui l ont eue, quand, et pourquoi elle
 * leur a ete retiree.</p>
 *
 * <p>Un index unique partiel garantit qu il n y a qu une affectation ouverte
 * ({@code date_fin IS NULL}) a la fois — I-30.</p>
 */
@Entity
@Table(name = "affectation_conversation")
public class AffectationConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "responsable_id", nullable = false)
    private Long responsableId;

    /** {@code null} si le responsable a pris la conversation lui-meme. */
    @Column(name = "affecte_par")
    private Long affectePar;

    @Column(columnDefinition = "text")
    private String motif;

    @Column(name = "date_debut", nullable = false, updatable = false)
    private Instant dateDebut = Instant.now();

    @Column(name = "date_fin")
    private Instant dateFin;

    protected AffectationConversation() {
    }

    public AffectationConversation(Long conversationId, Long responsableId, Long affectePar) {
        this.conversationId = conversationId;
        this.responsableId = responsableId;
        this.affectePar = affectePar;
    }

    public void cloturer(String motif) {
        this.dateFin = Instant.now();
        this.motif = motif;
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public Long getResponsableId() { return responsableId; }
    public Long getAffectePar() { return affectePar; }
    public String getMotif() { return motif; }
    public Instant getDateDebut() { return dateDebut; }
    public Instant getDateFin() { return dateFin; }
}
