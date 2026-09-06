package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * La remise de la marchandise au client, contre un code.
 *
 * <p>Le {@code code_retrait} est unique et c est le SEUL moyen de prouver la
 * remise. Sans lui, la parole du client s oppose a celle du point de retrait,
 * et personne ne peut trancher.</p>
 */
@Entity
@Table(name = "retrait_marchandise")
public class RetraitMarchandise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expedition_id", nullable = false)
    private Long expeditionId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "code_retrait", nullable = false, length = 20)
    private String codeRetrait;

    @Column(nullable = false, length = 20)
    private String statut = "EN_ATTENTE";

    @Column(name = "confirme_par")
    private Long confirmePar;

    @Column(name = "date_retrait")
    private Instant dateRetrait;

    protected RetraitMarchandise() {
    }

    public RetraitMarchandise(Long expeditionId, Long clientId, String codeRetrait) {
        this.expeditionId = expeditionId;
        this.clientId = clientId;
        this.codeRetrait = codeRetrait;
    }

    /**
     * La contrainte {@code retrait_confirmation_coherente} exige que
     * confirme_par ET date_retrait soient renseignes des que le statut vaut
     * CONFIRME. Les trois sont donc poses ensemble, ici et nulle part ailleurs.
     */
    void confirmer(Long responsableId) {
        this.statut = "CONFIRME";
        this.confirmePar = responsableId;
        this.dateRetrait = Instant.now();
    }

    void refuser() {
        this.statut = "REFUSE";
    }

    public boolean estConfirme() {
        return "CONFIRME".equals(statut);
    }

    public Long getId() { return id; }
    public Long getExpeditionId() { return expeditionId; }
    public Long getClientId() { return clientId; }
    public String getCodeRetrait() { return codeRetrait; }
    public String getStatut() { return statut; }
    public Long getConfirmePar() { return confirmePar; }
    public Instant getDateRetrait() { return dateRetrait; }
}
