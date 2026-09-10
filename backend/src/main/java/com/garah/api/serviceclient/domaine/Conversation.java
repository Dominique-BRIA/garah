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
    @Column(name = "pris_par")
    private Long prisPar;

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

    /**
     * Qui a clos, et non pas seulement quand.
     *
     * <p>🎯 {@code dateCloture} disait <b>quand</b>. Rien ne disait <b>qui</b>.
     * Sur un litige, savoir qu'une conversation a été close le 3 mars à 14h12
     * sans savoir par qui ne sert à rien — c'est exactement la question
     * posée.</p>
     *
     * <p>⚠️ {@code null} pour les conversations closes <b>avant</b> V33 : la
     * base ne l'a jamais su. Y écrire {@code prisPar} rétroactivement serait
     * une supposition, et une supposition dans un journal qu'on relit sur
     * litige est pire qu'une case vide.</p>
     */
    @Column(name = "clos_par")
    private Long closPar;

    /**
     * La commande dont parle cette conversation, quand elle en parle.
     *
     * <p>⚠️ A ne pas confondre avec {@code commande.conversation_id}, qui dit
     * de quelle conversation une commande est NEE. Voir V34.</p>
     */
    @Column(name = "commande_id")
    private Long commandeId;

    /**
     * La discussion « Assistance GARAH » du client.
     *
     * <p>🎯 UNE par client — un index unique partiel le garantit (V35) — et
     * JAMAIS close — une contrainte le garantit aussi. C'est le canal
     * permanent entre GARAH et le client : il y pose une question sans passer
     * par un produit, et l'équipe y écrit une offre, une information, un
     * rappel des règles.</p>
     */
    @Column(nullable = false)
    private boolean assistance;

    /** Le sujet de l'Assistance, tel que le client le lit. */
    public static final String SUJET_ASSISTANCE = "Assistance GARAH";

    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Message> messages = new ArrayList<>();

    protected Conversation() {
    }

    public Conversation(Long clientId, String sujet) {
        this.clientId = clientId;
        this.sujet = sujet;
    }

    /**
     * Une conversation ouverte par le SYSTEME, a propos d une commande.
     *
     * <p>Elle nait {@link StatutConversation#INFORMATION} : personne n attend
     * de reponse, et elle n entre donc pas dans la file de l equipe.</p>
     */
    static Conversation annonce(Long clientId, Long commandeId, String sujet) {
        Conversation c = new Conversation(clientId, sujet);
        c.statut = StatutConversation.INFORMATION;
        c.commandeId = commandeId;
        return c;
    }

    /** Un message ecrit par le systeme, et par personne. */
    Message annoncer(String contenu) {
        Message message = new Message(this, null, contenu);
        messages.add(message);
        return message;
    }

    /**
     * Le client a repondu a une annonce : desormais, il attend quelqu un.
     *
     * <p>Rend {@code true} seulement si le passage a eu lieu, pour que
     * l appelant ne previenne l equipe qu une fois.</p>
     */
    boolean attendreUnConseiller() {
        if (statut != StatutConversation.INFORMATION) {
            return false;
        }
        this.statut = StatutConversation.WAITING;
        return true;
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
        this.prisPar = null;
        this.statut = StatutConversation.WAITING;
        this.dateAffectation = null;
    }

    void fermer(Long parQui) {
        this.statut = StatutConversation.CLOSED;
        this.dateCloture = Instant.now();
        this.closPar = parQui;
    }

    /**
     * Prend la conversation, si personne ne l'a encore prise.
     *
     * <p>⚠️ Rend {@code true} SEULEMENT si la prise a eu lieu. L'appelant s'en
     * sert pour ne journaliser une affectation que lorsqu'il y en a une :
     * enregistrer une prise à chaque réponse remplirait l'historique de lignes
     * identiques.</p>
     */
    boolean prendreSiLibre(Long parQui) {
        // INFORMATION aussi : un conseiller qui ecrit dans une annonce la
        // prend, exactement comme il prendrait une conversation en attente.
        boolean libre = statut == StatutConversation.WAITING
                || statut == StatutConversation.INFORMATION;
        if (!libre || parQui == null) {
            return false;
        }
        this.prisPar = parQui;
        this.statut = StatutConversation.ASSIGNED;
        this.dateAffectation = Instant.now();
        return true;
    }

    public boolean estFermee() {
        return statut == StatutConversation.CLOSED;
    }

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public Long getPrisPar() { return prisPar; }
    public String getSujet() { return sujet; }
    public StatutConversation getStatut() { return statut; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateAffectation() { return dateAffectation; }
    public Instant getDateCloture() { return dateCloture; }
    public Long getClosPar() { return closPar; }
    public Long getCommandeId() { return commandeId; }
    public boolean estAssistance() { return assistance; }
    public List<Message> getMessages() { return messages; }
}
