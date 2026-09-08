package com.garah.api.stock.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une ligne du journal de stock. <b>Immuable</b> (I-18).
 *
 * <p>Aucun {@code UPDATE}, aucun {@code DELETE} : une erreur se corrige par un
 * mouvement inverse. C'est le même principe que {@code ecriture_marchand} —
 * on n'efface pas l'histoire, on l'allonge.</p>
 *
 * <p>Chaque ligne décrit <b>un seul</b> compteur (V16). Une réservation en
 * produit donc deux, dans la même transaction.</p>
 */
@Entity
@Table(name = "mouvement_stock")
public class MouvementStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeMouvement type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompteurStock compteur;

    /** Signée : négative pour une sortie, positive pour une entrée. Jamais nulle. */
    @Column(nullable = false)
    private int quantite;

    @Column(name = "quantite_avant", nullable = false)
    private int quantiteAvant;

    @Column(name = "quantite_apres", nullable = false)
    private int quantiteApres;

    @Enumerated(EnumType.STRING)
    @Column(name = "origine_type", nullable = false, length = 20)
    private OrigineMouvement origineType;

    @Column(name = "origine_id")
    private Long origineId;

    /**
     * Qui a enregistre ce mouvement — un UTILISATEUR, quel que soit son type.
     *
     * <p>⚠️ La colonne s appelait {@code responsable_id} et referencait la
     * table {@code responsable}. Un ADMIN n y a aucune ligne : il agit sur le
     * systeme, il n occupe pas un poste. Une reception saisie par un
     * administrateur violait donc une cle etrangere (V28).</p>
     *
     * <p>{@code null} quand le mouvement vient du systeme : une reservation
     * posee par une commande n a pas d auteur humain.</p>
     */
    @Column(name = "auteur_id")
    private Long auteurId;

    @Column(columnDefinition = "text")
    private String commentaire;

    @Column(name = "date_operation", nullable = false, updatable = false)
    private Instant dateOperation = Instant.now();

    protected MouvementStock() {
    }

    MouvementStock(Long stockId, TypeMouvement type, CompteurStock compteur, int quantite,
                   int quantiteAvant, OrigineMouvement origineType, Long origineId,
                   Long auteurId, String commentaire) {
        this.stockId = stockId;
        this.type = type;
        this.compteur = compteur;
        this.quantite = quantite;
        this.quantiteAvant = quantiteAvant;
        this.quantiteApres = quantiteAvant + quantite;   // respecte le CHECK I-16
        this.origineType = origineType;
        this.origineId = origineId;
        this.auteurId = auteurId;
        this.commentaire = commentaire;
    }

    public Long getId() { return id; }
    public Long getStockId() { return stockId; }
    public TypeMouvement getType() { return type; }
    public CompteurStock getCompteur() { return compteur; }
    public int getQuantite() { return quantite; }
    public int getQuantiteAvant() { return quantiteAvant; }
    public int getQuantiteApres() { return quantiteApres; }
    public OrigineMouvement getOrigineType() { return origineType; }
    public Long getOrigineId() { return origineId; }
    public Long getAuteurId() { return auteurId; }
    public String getCommentaire() { return commentaire; }
    public Instant getDateOperation() { return dateOperation; }
}
