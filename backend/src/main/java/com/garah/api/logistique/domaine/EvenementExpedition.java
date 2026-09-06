package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un fait date et IMMUABLE du parcours d un colis.
 *
 * <p>C est la verite. Le statut du colis n en est qu une projection.</p>
 *
 * <p>⚠️ Le lieu N EST PAS contraint a appartenir a l itineraire prevu. Une
 * route coupee, un deroutement, ca arrive. Une contrainte qui empeche
 * d enregistrer la realite est une mauvaise contrainte : l operateur
 * saisirait n importe quoi d autre pour continuer son travail, et la
 * tracabilite serait perdue (correction A9).</p>
 */
@Entity
@Table(name = "evenement_expedition")
public class EvenementExpedition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "colis_id", nullable = false)
    private Long colisId;

    @Column(name = "lieu_id", nullable = false)
    private Long lieuId;

    /** Null quand l evenement est automatique. */
    @Column(name = "responsable_id")
    private Long responsableId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeEvenement type;

    @Column(columnDefinition = "text")
    private String observation;

    @Column(name = "date_heure", nullable = false, updatable = false)
    private Instant dateHeure = Instant.now();

    protected EvenementExpedition() {
    }

    public EvenementExpedition(Long colisId, Long lieuId, Long responsableId,
                               TypeEvenement type, String observation) {
        this.colisId = colisId;
        this.lieuId = lieuId;
        this.responsableId = responsableId;
        this.type = type;
        this.observation = observation;
    }

    public Long getId() { return id; }
    public Long getColisId() { return colisId; }
    public Long getLieuId() { return lieuId; }
    public Long getResponsableId() { return responsableId; }
    public TypeEvenement getType() { return type; }
    public String getObservation() { return observation; }
    public Instant getDateHeure() { return dateHeure; }
}
