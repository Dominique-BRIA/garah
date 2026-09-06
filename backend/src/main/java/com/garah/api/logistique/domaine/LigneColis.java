package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

/**
 * Ce qu il y a REELLEMENT dans un colis (correction A13).
 *
 * <p>Sans cette table, la question « quels articles sont dans le colis n 3 ? »
 * n avait tout simplement pas de reponse — et une commande repartie en
 * plusieurs colis devenait intracable.</p>
 *
 * <p>Un TRIGGER (I-35) garantit qu on ne met pas en colis plus que ce qui a
 * ete commande : SQL ne sait pas exprimer « la somme des lignes filles ne
 * depasse pas une valeur de la ligne mere ».</p>
 */
@Entity
@Table(name = "ligne_colis")
public class LigneColis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "colis_id")
    private Colis colis;

    @Column(name = "ligne_commande_id", nullable = false)
    private Long ligneCommandeId;

    @Column(nullable = false)
    private int quantite;

    protected LigneColis() {
    }

    LigneColis(Colis colis, Long ligneCommandeId, int quantite) {
        this.colis = colis;
        this.ligneCommandeId = ligneCommandeId;
        this.quantite = quantite;
    }

    public Long getId() { return id; }
    public Colis getColis() { return colis; }
    public Long getLigneCommandeId() { return ligneCommandeId; }
    public int getQuantite() { return quantite; }
}
