package com.garah.api.surveillance.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un score de risque calculé, avec son explication.
 *
 * <p>Chaque calcul crée une <b>nouvelle ligne</b> — on n'écrase jamais le
 * précédent. Le score est un fait daté, pas un état : savoir qu'un client
 * était à 78 le 6 septembre et à 12 le 20 septembre raconte quelque chose que
 * la seule valeur courante ne dit pas.</p>
 */
@Entity
@Table(name = "score_risque_client")
public class ScoreRisqueClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NiveauRisque niveau;

    /**
     * Indispensable : deux scores calculés par deux versions du calcul ne sont
     * pas comparables. Sans cette colonne, un tableau d'évolution mentirait.
     */
    @Column(name = "version_algorithme", nullable = false, length = 20)
    private String versionAlgorithme;

    /**
     * Les signaux et leurs poids, en {@code jsonb}.
     *
     * <p>Pourquoi du JSON plutôt qu'une table fille : la structure <b>varie
     * selon la version</b> de l'algorithme. Une table figée obligerait à une
     * migration à chaque nouveau signal, et les anciennes lignes n'auraient
     * pas les nouvelles colonnes.</p>
     *
     * <p>C'est le bon usage de {@code jsonb} : une donnée qu'on <b>affiche</b>
     * et qu'on ne joint jamais. Ce qu'on filtre doit rester une vraie colonne
     * (chapitre 03 §14).</p>
     */
    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String details = "{}";

    @Column(name = "date_calcul", nullable = false, updatable = false)
    private Instant dateCalcul = Instant.now();

    protected ScoreRisqueClient() {
    }

    public ScoreRisqueClient(Long clientId, BigDecimal score, NiveauRisque niveau,
                             String versionAlgorithme, String details) {
        this.clientId = clientId;
        this.score = score;
        this.niveau = niveau;
        this.versionAlgorithme = versionAlgorithme;
        this.details = details;
    }

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public BigDecimal getScore() { return score; }
    public NiveauRisque getNiveau() { return niveau; }
    public String getVersionAlgorithme() { return versionAlgorithme; }
    public String getDetails() { return details; }
    public Instant getDateCalcul() { return dateCalcul; }
}
