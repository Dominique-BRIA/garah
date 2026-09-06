package com.garah.api.commerce.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une ligne de panier.
 *
 * <p>Elle ne contient <b>ni prix ni désignation</b> — seulement la variante et
 * la quantité. C'est délibéré : le prix d'un panier doit être celui
 * d'<b>aujourd'hui</b>, pas celui du jour où l'article y a été mis.</p>
 *
 * <p>La photographie (chapitre 03) commence à la <b>commande</b>, pas au
 * panier. Un panier est une intention ; une commande est un engagement.</p>
 */
@Entity
@Table(name = "ligne_panier")
public class LignePanier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "panier_id")
    private Panier panier;

    @Column(name = "variante_id", nullable = false)
    private Long varianteId;

    @Column(nullable = false)
    private int quantite;

    @Column(name = "date_ajout", nullable = false, updatable = false)
    private Instant dateAjout = Instant.now();

    protected LignePanier() {
    }

    LignePanier(Panier panier, Long varianteId, int quantite) {
        this.panier = panier;
        this.varianteId = varianteId;
        this.quantite = quantite;
    }

    void augmenter(int supplement) {
        this.quantite += supplement;
    }

    public void definirQuantite(int quantite) {
        this.quantite = quantite;
    }

    public Long getId() { return id; }
    public Panier getPanier() { return panier; }
    public Long getVarianteId() { return varianteId; }
    public int getQuantite() { return quantite; }
}
