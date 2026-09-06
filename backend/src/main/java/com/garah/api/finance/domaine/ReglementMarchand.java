package com.garah.api.finance.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un versement effectue au marchand.
 *
 * <p>Le reglement est une PIECE, pas une ecriture. Quand il passe a PAYE, il
 * PRODUIT une ecriture REGLEMENT negative dans le grand livre — et c est
 * elle qui fait baisser la dette.</p>
 *
 * <p>Distinguer les deux permet de preparer un reglement (PREVU) sans encore
 * toucher au solde, et de l annuler sans laisser de trace comptable fausse.</p>
 */
@Entity
@Table(name = "reglement_marchand")
public class ReglementMarchand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String numero;

    @Column(name = "marchand_id", nullable = false)
    private Long marchandId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(nullable = false, length = 3)
    private String devise = "XAF";

    @Column(nullable = false, length = 20)
    private String moyen;

    @Column(length = 100)
    private String reference;

    @Column(nullable = false, length = 20)
    private String statut = "PREVU";

    @Column(name = "cree_par")
    private Long creePar;

    @Column(name = "date_reglement")
    private Instant dateReglement;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    protected ReglementMarchand() {
    }

    public ReglementMarchand(String numero, Long marchandId, BigDecimal montant,
                             String moyen, Long creePar) {
        this.numero = numero;
        this.marchandId = marchandId;
        this.montant = montant;
        this.moyen = moyen;
        this.creePar = creePar;
    }

    /**
     * La contrainte {@code reglement_marchand_paiement_coherent} exige une
     * date des que le statut vaut PAYE. Les deux sont donc poses ensemble.
     */
    void payer(String reference) {
        this.statut = "PAYE";
        this.reference = reference;
        this.dateReglement = Instant.now();
    }

    void annuler() {
        this.statut = "ANNULE";
    }

    public boolean estPaye() {
        return "PAYE".equals(statut);
    }

    public Long getId() { return id; }
    public String getNumero() { return numero; }
    public Long getMarchandId() { return marchandId; }
    public BigDecimal getMontant() { return montant; }
    public String getMoyen() { return moyen; }
    public String getReference() { return reference; }
    public String getStatut() { return statut; }
    public Long getCreePar() { return creePar; }
    public Instant getDateReglement() { return dateReglement; }
}
