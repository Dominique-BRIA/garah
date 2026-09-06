package com.garah.api.stock.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * L'état physique actuel d'une variante.
 *
 * <p>Cette table ne contient <b>que des états</b>, jamais de cumul
 * (correction A5). « Combien en a-t-on vendu depuis janvier ? » se déduit des
 * mouvements, pas d'une colonne — sinon on finirait par écrire
 * {@code disponible + reservee + vendue}, qui n'a aucun sens.</p>
 *
 * <p>La variante est référencée par son identifiant, pas par une association :
 * le stock n'a pas besoin de connaître le catalogue pour compter des unités,
 * et la clé étrangère reste garantie par la base.</p>
 */
@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "variante_id", nullable = false)
    private Long varianteId;

    @Column(name = "quantite_disponible", nullable = false)
    private int quantiteDisponible = 0;

    @Column(name = "quantite_reservee", nullable = false)
    private int quantiteReservee = 0;

    @Column(name = "quantite_endommagee", nullable = false)
    private int quantiteEndommagee = 0;

    @Column(name = "seuil_alerte", nullable = false)
    private int seuilAlerte = 0;

    @Column(name = "date_modification", nullable = false)
    private Instant dateModification = Instant.now();

    protected Stock() {
    }

    public Stock(Long varianteId) {
        this.varianteId = varianteId;
    }

    /** Lecture d'un compteur par son nom — utilisé pour journaliser avant/après. */
    int valeur(CompteurStock compteur) {
        return switch (compteur) {
            case DISPONIBLE -> quantiteDisponible;
            case RESERVEE -> quantiteReservee;
            case ENDOMMAGEE -> quantiteEndommagee;
        };
    }

    /**
     * Applique une variation à un compteur.
     *
     * <p>La négativité est vérifiée ici <b>et</b> par la contrainte
     * {@code stock_quantites_positives}. Ce n'est pas redondant : ici on
     * produit un message métier utilisable, là-bas on garantit qu'aucun chemin
     * ne peut l'éviter — pas même un script d'import (chapitre 04 §3).</p>
     */
    void appliquer(CompteurStock compteur, int variation) {
        int apres = valeur(compteur) + variation;
        if (apres < 0) {
            throw new IllegalStateException(
                    "Le compteur " + compteur + " deviendrait négatif (" + apres + ").");
        }
        switch (compteur) {
            case DISPONIBLE -> quantiteDisponible = apres;
            case RESERVEE -> quantiteReservee = apres;
            case ENDOMMAGEE -> quantiteEndommagee = apres;
        }
        this.dateModification = Instant.now();
    }

    public boolean sousLeSeuil() {
        return quantiteDisponible <= seuilAlerte;
    }

    public Long getId() { return id; }
    public Long getVarianteId() { return varianteId; }
    public int getQuantiteDisponible() { return quantiteDisponible; }
    public int getQuantiteReservee() { return quantiteReservee; }
    public int getQuantiteEndommagee() { return quantiteEndommagee; }
    public int getSeuilAlerte() { return seuilAlerte; }

    public void setSeuilAlerte(int seuil) { this.seuilAlerte = seuil; }
}
