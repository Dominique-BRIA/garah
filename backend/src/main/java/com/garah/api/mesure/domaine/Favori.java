package com.garah.api.mesure.domaine;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Un produit mis en favori par un client.
 *
 * <p>Cle primaire composite naturelle : un client ne peut mettre un produit
 * en favori qu une fois. Aucune colonne d identifiant technique n est
 * necessaire — la paire EST l identite.</p>
 */
@Entity
@Table(name = "favori")
public class Favori {

    @Embeddable
    public static class Cle implements Serializable {

        @Column(name = "client_id")
        private Long clientId;

        @Column(name = "produit_id")
        private Long produitId;

        protected Cle() {
        }

        public Cle(Long clientId, Long produitId) {
            this.clientId = clientId;
            this.produitId = produitId;
        }

        public Long getClientId() { return clientId; }
        public Long getProduitId() { return produitId; }

        @Override
        public boolean equals(Object autre) {
            return autre instanceof Cle c
                    && Objects.equals(clientId, c.clientId)
                    && Objects.equals(produitId, c.produitId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId, produitId);
        }
    }

    @EmbeddedId
    private Cle cle;

    @Column(name = "date_ajout", nullable = false, updatable = false)
    private Instant dateAjout = Instant.now();

    protected Favori() {
    }

    public Favori(Long clientId, Long produitId) {
        this.cle = new Cle(clientId, produitId);
    }

    public Cle getCle() { return cle; }
    public Instant getDateAjout() { return dateAjout; }
}
