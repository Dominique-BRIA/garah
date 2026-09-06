package com.garah.api.commerce.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une tentative de paiement, réussie ou non.
 *
 * <p><b>Pourquoi conserver les échecs.</b> La §18 de la spécification veut
 * surveiller les « échecs de paiement » — c'est un signal du score de risque
 * (§19). Si on n'enregistrait que les paiements réussis, ce signal
 * n'existerait tout simplement pas.</p>
 *
 * <p>Et un échec n'est pas toujours une fraude : solde insuffisant, code
 * erroné, opérateur indisponible. C'est la <b>répétition</b> qui fait le
 * signal, comme pour les échecs de connexion (chapitre 08 §9).</p>
 */
@Entity
@Table(name = "tentative_paiement")
public class TentativePaiement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paiement_id", nullable = false)
    private Long paiementId;

    @Column(nullable = false, length = 20)
    private String statut;

    @Column(name = "code_erreur", length = 50)
    private String codeErreur;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "date_tentative", nullable = false, updatable = false)
    private Instant dateTentative = Instant.now();

    protected TentativePaiement() {
    }

    public TentativePaiement(Long paiementId, String statut, String codeErreur, String message) {
        this.paiementId = paiementId;
        this.statut = statut;
        this.codeErreur = codeErreur;
        this.message = message;
    }

    public Long getId() { return id; }
    public Long getPaiementId() { return paiementId; }
    public String getStatut() { return statut; }
    public String getCodeErreur() { return codeErreur; }
    public String getMessage() { return message; }
    public Instant getDateTentative() { return dateTentative; }
}
