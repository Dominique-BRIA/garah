package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Une exception individuelle de permission : {@code ADD} ou {@code REMOVE}.
 *
 * <p>La clé primaire composite {@code (responsable_id, cas_utilisation_id)}
 * garantit l'invariant I-04 : une permission ne peut pas être à la fois
 * ajoutée et retirée pour la même personne. C'est la base qui l'impose,
 * pas le code.</p>
 *
 * <p>Le {@code motif} n'est pas décoratif : six mois plus tard, « pourquoi
 * Paul a-t-il ce droit que sa catégorie ne donne pas ? » est une vraie
 * question d'audit.</p>
 */
@Entity
@Table(name = "responsable_cas_utilisation")
public class ResponsableCasUtilisation {

    @Embeddable
    public static class Cle implements Serializable {

        @Column(name = "responsable_id")
        private Long responsableId;

        @Column(name = "cas_utilisation_id")
        private Long casUtilisationId;

        protected Cle() {
        }

        public Cle(Long responsableId, Long casUtilisationId) {
            this.responsableId = responsableId;
            this.casUtilisationId = casUtilisationId;
        }

        public Long getResponsableId() { return responsableId; }
        public Long getCasUtilisationId() { return casUtilisationId; }

        @Override
        public boolean equals(Object autre) {
            return autre instanceof Cle c
                    && Objects.equals(responsableId, c.responsableId)
                    && Objects.equals(casUtilisationId, c.casUtilisationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(responsableId, casUtilisationId);
        }
    }

    @EmbeddedId
    private Cle cle = new Cle();

    @MapsId("responsableId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "responsable_id")
    private Responsable responsable;

    @MapsId("casUtilisationId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cas_utilisation_id")
    private CasUtilisation casUtilisation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TypeException type;

    @Column(columnDefinition = "text")
    private String motif;

    @Column(name = "accorde_par")
    private Long accordePar;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    protected ResponsableCasUtilisation() {
    }

    public ResponsableCasUtilisation(Responsable responsable, CasUtilisation casUtilisation,
                                     TypeException type, String motif, Long accordePar) {
        this.responsable = responsable;
        this.casUtilisation = casUtilisation;
        this.type = type;
        this.motif = motif;
        this.accordePar = accordePar;
    }

    public Cle getCle() { return cle; }
    public Responsable getResponsable() { return responsable; }
    public CasUtilisation getCasUtilisation() { return casUtilisation; }
    public TypeException getType() { return type; }
    public String getMotif() { return motif; }
    public Long getAccordePar() { return accordePar; }
    public Instant getDateCreation() { return dateCreation; }

    @Override
    public boolean equals(Object autre) {
        return autre instanceof ResponsableCasUtilisation r && Objects.equals(cle, r.cle);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(cle);
    }
}
