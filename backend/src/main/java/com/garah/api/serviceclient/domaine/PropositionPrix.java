package com.garah.api.serviceclient.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Une proposition de prix dans une négociation (correction A6).
 *
 * <p>Le modèle initial ne disait <b>ni sur quoi</b> portait la proposition,
 * <b>ni pour quelle quantité</b>, <b>ni qui</b> proposait, <b>ni jusqu'à
 * quand</b> elle valait. Les quatre manquaient, et sans eux la négociation
 * n'est qu'un chiffre dans une discussion.</p>
 *
 * <p>Les contre-propositions se <b>chaînent</b> par
 * {@code propositionParenteId} : on peut reconstituer toute la négociation,
 * ce qui compte quand un client conteste un prix.</p>
 */
@Entity
@Table(name = "proposition_prix")
public class PropositionPrix {

    /** Validité par défaut : au-delà, une remise négociée n'a plus de sens. */
    public static final Duration VALIDITE_PAR_DEFAUT = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "variante_id", nullable = false)
    private Long varianteId;

    /** La proposition à laquelle celle-ci répond. Reconstitue le fil. */
    @Column(name = "proposition_parente_id")
    private Long propositionParenteId;

    @Column(nullable = false)
    private int quantite;

    @Column(name = "prix_unitaire_propose", nullable = false, precision = 15, scale = 2)
    private BigDecimal prixUnitairePropose;

    @Column(name = "auteur_id", nullable = false)
    private Long auteurId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SensProposition sens;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutProposition statut = StatutProposition.PROPOSEE;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_expiration", nullable = false)
    private Instant dateExpiration;

    protected PropositionPrix() {
    }

    public PropositionPrix(Long conversationId, Long varianteId, int quantite,
                           BigDecimal prixUnitairePropose, Long auteurId, SensProposition sens,
                           Duration validite) {
        this.conversationId = conversationId;
        this.varianteId = varianteId;
        this.quantite = quantite;
        this.prixUnitairePropose = prixUnitairePropose;
        this.auteurId = auteurId;
        this.sens = sens;
        this.dateExpiration = Instant.now().plus(
                validite == null ? VALIDITE_PAR_DEFAUT : validite);
    }

    public boolean estExpiree() {
        return Instant.now().isAfter(dateExpiration);
    }

    public boolean estUtilisable() {
        return statut == StatutProposition.ACCEPTEE && !estExpiree();
    }

    void accepter() {
        this.statut = StatutProposition.ACCEPTEE;
    }

    void refuser() {
        this.statut = StatutProposition.REFUSEE;
    }

    void expirer() {
        this.statut = StatutProposition.EXPIREE;
    }

    void consommer() {
        this.statut = StatutProposition.CONSOMMEE;
    }

    void rattacherA(Long parenteId) {
        this.propositionParenteId = parenteId;
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public Long getVarianteId() { return varianteId; }
    public Long getPropositionParenteId() { return propositionParenteId; }
    public int getQuantite() { return quantite; }
    public BigDecimal getPrixUnitairePropose() { return prixUnitairePropose; }
    public Long getAuteurId() { return auteurId; }
    public SensProposition getSens() { return sens; }
    public StatutProposition getStatut() { return statut; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateExpiration() { return dateExpiration; }
}
