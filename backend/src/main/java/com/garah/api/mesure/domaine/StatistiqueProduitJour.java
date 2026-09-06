package com.garah.api.mesure.domaine;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * L agregat journalier d un produit.
 *
 * <p>C est le principe ECRITURE DETAILLEE / LECTURE AGREGEE :</p>
 *
 * <pre>
 * vue_produit                 1 ligne par consultation   → des millions
 * statistique_produit_jour    1 ligne par produit/jour   → minuscule
 * </pre>
 *
 * <p>Compter les vues d un produit sur 30 jours en scannant vue_produit
 * devient lent des quelques millions de lignes. Ici, on lit 30 lignes.</p>
 *
 * <p>Conserve INDEFINIMENT, contrairement au detail purge a 90 jours (D-15) :
 * une ligne par produit et par jour, c est negligeable.</p>
 */
@Entity
@Table(name = "statistique_produit_jour")
public class StatistiqueProduitJour {

    @Embeddable
    public static class Cle implements Serializable {

        @Column(name = "produit_id")
        private Long produitId;

        @Column(name = "jour")
        private LocalDate jour;

        protected Cle() {
        }

        public Cle(Long produitId, LocalDate jour) {
            this.produitId = produitId;
            this.jour = jour;
        }

        public Long getProduitId() { return produitId; }
        public LocalDate getJour() { return jour; }

        @Override
        public boolean equals(Object autre) {
            return autre instanceof Cle c
                    && Objects.equals(produitId, c.produitId)
                    && Objects.equals(jour, c.jour);
        }

        @Override
        public int hashCode() {
            return Objects.hash(produitId, jour);
        }
    }

    @EmbeddedId
    private Cle cle;

    @Column(nullable = false)
    private int vues = 0;

    /** Distinctes par session : un rafraichissement ne fait pas un visiteur. */
    @Column(name = "vues_uniques", nullable = false)
    private int vuesUniques = 0;

    @Column(name = "ajouts_panier", nullable = false)
    private int ajoutsPanier = 0;

    @Column(nullable = false)
    private int commandes = 0;

    @Column(name = "quantite_vendue", nullable = false)
    private int quantiteVendue = 0;

    @Column(name = "chiffre_affaires", nullable = false, precision = 15, scale = 2)
    private BigDecimal chiffreAffaires = BigDecimal.ZERO;

    @Column(nullable = false)
    private int retours = 0;

    protected StatistiqueProduitJour() {
    }

    public Cle getCle() { return cle; }
    public int getVues() { return vues; }
    public int getVuesUniques() { return vuesUniques; }
    public int getAjoutsPanier() { return ajoutsPanier; }
    public int getCommandes() { return commandes; }
    public int getQuantiteVendue() { return quantiteVendue; }
    public BigDecimal getChiffreAffaires() { return chiffreAffaires; }
    public int getRetours() { return retours; }
}
