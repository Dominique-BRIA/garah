package com.garah.api.surveillance.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une alerte soumise a un humain.
 *
 * <p>Regle fondatrice n 2 : la surveillance OBSERVE, elle ne DECIDE pas.
 * Cette table est le point de rencontre entre la machine et l humain — et
 * c est deliberement l humain qui tranche.</p>
 *
 * <p>Le champ {@code decision} enregistre ce qu il a decide et pourquoi. Une
 * alerte traitee sans decision ecrite ne sert a rien : personne ne saura si
 * le compte a ete verifie ou simplement ignore.</p>
 */
@Entity
@Table(name = "alerte_securite")
public class AlerteSecurite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "evenement_securite_id")
    private Long evenementSecuriteId;

    @Column(nullable = false, length = 40)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GraviteEvenement gravite;

    @Column(nullable = false, length = 20)
    private String statut = "OUVERTE";

    @Column(name = "traite_par")
    private Long traitePar;

    @Column(columnDefinition = "text")
    private String decision;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_traitement")
    private Instant dateTraitement;

    protected AlerteSecurite() {
    }

    public AlerteSecurite(Long clientId, String type, GraviteEvenement gravite,
                          Long evenementSecuriteId) {
        this.clientId = clientId;
        this.type = type;
        this.gravite = gravite;
        this.evenementSecuriteId = evenementSecuriteId;
    }

    /**
     * La contrainte {@code alerte_securite_traitement_coherent} exige
     * traite_par ET date_traitement des que le statut est TRAITEE ou IGNOREE.
     * Les trois sont donc poses ensemble.
     */
    void traiter(String statutFinal, Long responsableId, String decision) {
        this.statut = statutFinal;
        this.traitePar = responsableId;
        this.decision = decision;
        this.dateTraitement = Instant.now();
    }

    public boolean estOuverte() {
        return "OUVERTE".equals(statut) || "EN_COURS".equals(statut);
    }

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public Long getEvenementSecuriteId() { return evenementSecuriteId; }
    public String getType() { return type; }
    public GraviteEvenement getGravite() { return gravite; }
    public String getStatut() { return statut; }
    public Long getTraitePar() { return traitePar; }
    public String getDecision() { return decision; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateTraitement() { return dateTraitement; }
}
