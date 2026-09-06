package com.garah.api.finance.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Une ligne du grand livre marchand. <b>Immuable</b> (I-42).
 *
 * <p>C'est la correction la plus importante du chapitre 02 :</p>
 *
 * <blockquote>
 * On ne stocke JAMAIS un solde comme source de vérité.<br>
 * On stocke des écritures, et le solde est leur SOMME.
 * </blockquote>
 *
 * <p>La table {@code dette_marchand} de la spécification initiale a donc
 * disparu. Un solde stocké ment dès qu'une écriture est ratée — et plus
 * personne ne peut prouver la vérité.</p>
 *
 * <p>Aucun {@code UPDATE}, aucun {@code DELETE} : une erreur se corrige par
 * une écriture d'{@code AJUSTEMENT}. On n'efface pas l'histoire, on
 * l'allonge.</p>
 */
@Entity
@Table(name = "ecriture_marchand")
public class EcritureMarchand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marchand_id", nullable = false)
    private Long marchandId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TypeEcriture type;

    /** SIGNÉ. Positif = dû au marchand. Négatif = retiré de ce qu'on lui doit. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(nullable = false, length = 3)
    private String devise = "XAF";

    @Enumerated(EnumType.STRING)
    @Column(name = "origine_type", nullable = false, length = 25)
    private OrigineEcriture origineType;

    @Column(name = "origine_id", nullable = false)
    private Long origineId;

    @Column(length = 250)
    private String libelle;

    @Column(name = "cree_par")
    private Long creePar;

    @Column(name = "date_ecriture", nullable = false, updatable = false)
    private Instant dateEcriture = Instant.now();

    protected EcritureMarchand() {
    }

    /**
     * Le seul constructeur.
     *
     * <p>Il prend un montant <b>positif</b> et l'oriente selon le type. C'est
     * ce qui rend une inversion de signe impossible par construction : le
     * code appelant n'a jamais à se demander « positif ou négatif ? ».</p>
     */
    public EcritureMarchand(Long marchandId, TypeEcriture type, BigDecimal montantPositif,
                            OrigineEcriture origineType, Long origineId,
                            String libelle, Long creePar) {
        this.marchandId = marchandId;
        this.type = type;
        this.montant = type.orienter(montantPositif.abs());
        this.origineType = origineType;
        this.origineId = origineId;
        this.libelle = libelle;
        this.creePar = creePar;
    }

    /** Variante pour les ajustements, où le signe est décidé par l'appelant. */
    public static EcritureMarchand ajustement(Long marchandId, BigDecimal montantSigne,
                                              String libelle, Long creePar) {
        EcritureMarchand ecriture = new EcritureMarchand();
        ecriture.marchandId = marchandId;
        ecriture.type = TypeEcriture.AJUSTEMENT;
        ecriture.montant = montantSigne;
        ecriture.origineType = OrigineEcriture.MANUEL;
        ecriture.origineId = 0L;
        ecriture.libelle = libelle;
        ecriture.creePar = creePar;
        return ecriture;
    }

    public Long getId() { return id; }
    public Long getMarchandId() { return marchandId; }
    public TypeEcriture getType() { return type; }
    public BigDecimal getMontant() { return montant; }
    public String getDevise() { return devise; }
    public OrigineEcriture getOrigineType() { return origineType; }
    public Long getOrigineId() { return origineId; }
    public String getLibelle() { return libelle; }
    public Long getCreePar() { return creePar; }
    public Instant getDateEcriture() { return dateEcriture; }
}
