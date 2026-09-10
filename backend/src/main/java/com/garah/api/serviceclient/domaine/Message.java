package com.garah.api.serviceclient.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/** Un message dans une conversation. */
@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    /**
     * L auteur, client ou responsable indifferemment — ou PERSONNE.
     *
     * <p>On pointe vers {@code utilisateur} et non vers l un des deux : c est
     * la racine commune. Deux colonnes exclusives seraient plus precises et
     * beaucoup plus penibles a interroger.</p>
     *
     * <p>⚠️ {@code null} = une annonce du SYSTEME (V34). Tout code qui compare
     * l expediteur doit le faire dans ce sens : {@code id.equals(expediteur)},
     * jamais {@code expediteur.equals(id)}.</p>
     */
    @Column(name = "expediteur_id")
    private Long expediteurId;

    @Column(nullable = false, columnDefinition = "text")
    private String contenu;

    @Column(nullable = false)
    private boolean lu = false;

    @Column(name = "date_envoi", nullable = false, updatable = false)
    private Instant dateEnvoi = Instant.now();

    protected Message() {
    }

    Message(Conversation conversation, Long expediteurId, String contenu) {
        this.conversation = conversation;
        this.expediteurId = expediteurId;
        this.contenu = contenu;
    }

    public void marquerLu() {
        this.lu = true;
    }

    public Long getId() { return id; }
    public Conversation getConversation() { return conversation; }
    public Long getExpediteurId() { return expediteurId; }
    public String getContenu() { return contenu; }
    public boolean estLu() { return lu; }
    public Instant getDateEnvoi() { return dateEnvoi; }
}
