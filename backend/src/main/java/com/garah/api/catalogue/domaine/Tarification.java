package com.garah.api.catalogue.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un palier de prix pour une variante.
 *
 * <p>Le prix est <b>TTC</b> (D-11) : activer la TVA n'augmentera aucun prix
 * affiché, elle en sera extraite.</p>
 *
 * <p>L'entité est définie ici parce qu'elle appartient physiquement au
 * catalogue ; la logique de sélection du bon palier fait l'objet du
 * <b>chapitre 10</b>.</p>
 *
 * <p>⚠️ La base interdit tout chevauchement entre paliers d'une même variante,
 * en quantité <b>et</b> dans le temps, via une contrainte d'exclusion GiST
 * (I-10). Aucun code Java n'a besoin de le revérifier.</p>
 */
@Entity
@Table(name = "tarification")
public class Tarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variante_id")
    private Variante variante;

    @Column(name = "quantite_min", nullable = false)
    private int quantiteMin;

    /** {@code null} signifie « et au-delà ». */
    @Column(name = "quantite_max")
    private Integer quantiteMax;

    @Column(name = "prix_unitaire", nullable = false, precision = 15, scale = 2)
    private BigDecimal prixUnitaire;

    @Column(nullable = false, length = 3)
    private String devise = "XAF";

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut = LocalDate.now();

    @Column(name = "date_fin")
    private LocalDate dateFin;

    protected Tarification() {
    }

    public Tarification(Variante variante, int quantiteMin, Integer quantiteMax,
                        BigDecimal prixUnitaire) {
        this.variante = variante;
        this.quantiteMin = quantiteMin;
        this.quantiteMax = quantiteMax;
        this.prixUnitaire = prixUnitaire;
    }

    public Long getId() { return id; }
    public Variante getVariante() { return variante; }
    public int getQuantiteMin() { return quantiteMin; }
    public Integer getQuantiteMax() { return quantiteMax; }
    public BigDecimal getPrixUnitaire() { return prixUnitaire; }
    public String getDevise() { return devise; }
    public LocalDate getDateDebut() { return dateDebut; }
    public LocalDate getDateFin() { return dateFin; }

    public void setDateFin(LocalDate dateFin) { this.dateFin = dateFin; }
}
