package com.garah.api.serviceclient.domaine;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Un échange entre un client et un responsable.
 *
 * <p>Une conversation naît sans responsable ({@code WAITING}) et attend d'être
 * prise. C'est un modèle de <b>file d'attente partagée</b> : plusieurs
 * responsables voient la même liste, et le premier qui clique gagne.</p>
 *
 * <p>D'où le problème de concurrence traité dans {@code ServiceConversation} —
 * le même que le stock, avec une solution différente.</p>
 */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** {@code null} tant que la conversation est en attente. */
    @Column(name = "responsable_id")
    private Long responsableId;

    @Column(length = 200)
    private String sujet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutConversation statut = StatutConversation.WAITING;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_affectation")
    private Instant dateAffectation;

    @Column(name = "date_cloture")
    private Instant dateCloture;

    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Message> messages = new ArrayList<>();

    protected Conversation() {
    }

    public Conversation(Long clientId, String sujet) {
        this.clientId = clientId;
        this.sujet = sujet;
    }

    public Message ajouterMessage(Long expediteurId, String contenu) {
        Message message = new Message(this, expediteurId, contenu);
        messages.add(message);
        return message;
    }

    /**
     * Retire la conversation à son responsable et la remet en attente.
     *
     * <p>Réservé à l'Admin (§4 de la spécification). L'opération est tracée
     * dans {@code affectation_conversation} <b>et</b> dans l'audit : retirer
     * un dossier à quelqu'un est une décision qui doit pouvoir s'expliquer.</p>
     */
    void remettreEnAttente() {
        this.responsableId = null;
        this.statut = StatutConversation.WAITING;
        this.dateAffectation = null;
    }

    void fermer() {
        this.statut = StatutConversation.CLOSED;
        this.dateCloture = Instant.now();
    }

    public boolean estFermee() {
        return statut == StatutConversation.CLOSED;
    }

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public Long getResponsableId() { return responsableId; }
    public String getSujet() { return sujet; }
    public StatutConversation getStatut() { return statut; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateAffectation() { return dateAffectation; }
    public Instant getDateCloture() { return dateCloture; }
    public List<Message> getMessages() { return messages; }
}
