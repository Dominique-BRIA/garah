package com.garah.api.commerce.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un mouvement d'argent lié à une commande.
 *
 * <p><b>Le montant est toujours positif.</b> Le sens est porté par
 * {@link TypePaiement}, jamais par un signe : un remboursement stocké en
 * négatif casserait toutes les sommes, et il suffirait d'oublier un
 * {@code WHERE type = 'ENCAISSEMENT'} pour fausser un chiffre d'affaires.</p>
 *
 * <p>{@code referenceTransaction} est l'identifiant chez l'opérateur. Sans
 * elle, <b>aucun litige mobile money n'est arbitrable</b> : on ne peut pas
 * prouver qu'un paiement annoncé par le client est arrivé, ni l'inverse.</p>
 */
@Entity
@Table(name = "paiement")
public class Paiement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "commande_id", nullable = false)
    private Long commandeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypePaiement type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(nullable = false, length = 3)
    private String devise = "XAF";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MoyenPaiement moyen;

    /** Unique quand elle existe (V18) : le webhook peut être rejoué. */
    @Column(name = "reference_transaction", length = 100)
    private String referenceTransaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutPaiement statut = StatutPaiement.INITIE;

    /** Pour un remboursement : ce qui le justifie (RETOUR ou RECLAMATION). */
    @Column(name = "origine_type", length = 20)
    private String origineType;

    @Column(name = "origine_id")
    private Long origineId;

    @Column(name = "date_initiation", nullable = false, updatable = false)
    private Instant dateInitiation = Instant.now();

    @Column(name = "date_confirmation")
    private Instant dateConfirmation;

    protected Paiement() {
    }

    public static Paiement encaissement(Long commandeId, BigDecimal montant, MoyenPaiement moyen) {
        Paiement p = new Paiement();
        p.commandeId = commandeId;
        p.type = TypePaiement.ENCAISSEMENT;
        p.montant = montant;
        p.moyen = moyen;
        return p;
    }

    public static Paiement remboursement(Long commandeId, BigDecimal montant, MoyenPaiement moyen,
                                         String origineType, Long origineId) {
        Paiement p = new Paiement();
        p.commandeId = commandeId;
        p.type = TypePaiement.REMBOURSEMENT;
        p.montant = montant;
        p.moyen = moyen;
        p.origineType = origineType;
        p.origineId = origineId;
        return p;
    }

    public boolean estConfirme() {
        return statut == StatutPaiement.CONFIRME;
    }

    /**
     * Confirme le paiement.
     *
     * <p>La contrainte {@code paiement_confirmation_coherente} exige que
     * {@code date_confirmation} soit renseignée dès que le statut vaut
     * {@code CONFIRME}. Les deux sont donc posés ensemble, ici, et nulle part
     * ailleurs.</p>
     */
    void confirmer(String referenceTransaction) {
        this.referenceTransaction = referenceTransaction;
        this.statut = StatutPaiement.CONFIRME;
        this.dateConfirmation = Instant.now();
    }

    void echouer() {
        this.statut = StatutPaiement.ECHOUE;
    }

    void mettreEnAttente(String referenceTransaction) {
        this.referenceTransaction = referenceTransaction;
        this.statut = StatutPaiement.EN_ATTENTE;
    }

    public Long getId() { return id; }
    public Long getCommandeId() { return commandeId; }
    public TypePaiement getType() { return type; }
    public BigDecimal getMontant() { return montant; }
    public String getDevise() { return devise; }
    public MoyenPaiement getMoyen() { return moyen; }
    public String getReferenceTransaction() { return referenceTransaction; }
    public StatutPaiement getStatut() { return statut; }
    public String getOrigineType() { return origineType; }
    public Long getOrigineId() { return origineId; }
    public Instant getDateInitiation() { return dateInitiation; }
    public Instant getDateConfirmation() { return dateConfirmation; }
}
