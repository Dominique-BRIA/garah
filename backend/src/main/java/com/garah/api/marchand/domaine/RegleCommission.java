package com.garah.api.marchand.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un taux de commission applicable, avec sa période de validité.
 *
 * <p>C'est une <b>règle de calcul</b>, pas un montant. Le montant, lui, est
 * figé dans {@code ligne_commande} au moment de la vente.</p>
 *
 * <p><b>Pourquoi cette règle est datée</b>, alors que les frais d'acheminement
 * ne le sont pas (D-10) : on doit pouvoir <b>rejouer</b> un calcul de
 * commission en cas de litige avec un marchand. Le montant figé prouve ce
 * qu'on a prélevé ; la règle datée prouve qu'on avait le droit de le
 * prélever.</p>
 *
 * <p>Une règle peut cibler un marchand, une catégorie de produit, les deux, ou
 * aucun des deux. La plus <b>spécifique</b> gagne — c'est le rôle de
 * {@code priorite}.</p>
 */
@Entity
@Table(name = "regle_commission")
public class RegleCommission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = s'applique à tous les marchands. */
    @Column(name = "marchand_id")
    private Long marchandId;

    /** {@code null} = s'applique à toutes les catégories. */
    @Column(name = "categorie_produit_id")
    private Long categorieProduitId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taux;

    @Column(nullable = false)
    private int priorite = 0;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut = LocalDate.now();

    @Column(name = "date_fin")
    private LocalDate dateFin;

    protected RegleCommission() {
    }

    public RegleCommission(Long marchandId, Long categorieProduitId, BigDecimal taux, int priorite) {
        this.marchandId = marchandId;
        this.categorieProduitId = categorieProduitId;
        this.taux = taux;
        this.priorite = priorite;
    }

    public Long getId() { return id; }
    public Long getMarchandId() { return marchandId; }
    public Long getCategorieProduitId() { return categorieProduitId; }
    public BigDecimal getTaux() { return taux; }
    public int getPriorite() { return priorite; }
    public LocalDate getDateDebut() { return dateDebut; }
    public LocalDate getDateFin() { return dateFin; }

    public void setDateFin(LocalDate dateFin) { this.dateFin = dateFin; }
}
