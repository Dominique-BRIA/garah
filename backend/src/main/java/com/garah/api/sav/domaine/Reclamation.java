package com.garah.api.sav.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une reclamation client.
 *
 * <p>C est aussi la voie de recours du client qui veut annuler une commande
 * deja payee : il ne peut pas le faire seul (D-12), il reclame, et un humain
 * decide.</p>
 */
@Entity
@Table(name = "reclamation")
public class Reclamation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String numero;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "commande_id", nullable = false)
    private Long commandeId;

    @Column(name = "responsable_id")
    private Long responsableId;

    @Column(nullable = false, length = 50)
    private String motif;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutReclamation statut = StatutReclamation.OUVERTE;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_resolution")
    private Instant dateResolution;

    protected Reclamation() {
    }

    public Reclamation(String numero, Long clientId, Long commandeId,
                       String motif, String description) {
        this.numero = numero;
        this.clientId = clientId;
        this.commandeId = commandeId;
        this.motif = motif;
        this.description = description;
    }

    void prendreEnCharge(Long responsableId) {
        this.responsableId = responsableId;
        this.statut = StatutReclamation.EN_COURS;
    }

    /**
     * La contrainte {@code reclamation_resolution_coherente} exige une date de
     * resolution des que le statut est RESOLUE ou FERMEE. Les deux sont donc
     * poses ensemble.
     */
    void cloturer(StatutReclamation statutFinal) {
        this.statut = statutFinal;
        this.dateResolution = Instant.now();
    }

    public Long getId() { return id; }
    public String getNumero() { return numero; }
    public Long getClientId() { return clientId; }
    public Long getCommandeId() { return commandeId; }
    public Long getResponsableId() { return responsableId; }
    public String getMotif() { return motif; }
    public String getDescription() { return description; }
    public StatutReclamation getStatut() { return statut; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateResolution() { return dateResolution; }
}
