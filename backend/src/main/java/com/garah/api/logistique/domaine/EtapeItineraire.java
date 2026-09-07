package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

/**
 * Une étape intermédiaire d'un {@link Itineraire}.
 *
 * <p>Le {@code ordre} est <b>engendré</b>, jamais saisi : voir
 * {@link Itineraire#remplacerEtapes}. La contrainte {@code
 * etape_itineraire_ordre_unique} (I-34) refuse deux étapes de même rang dans
 * un même itinéraire — y compris de façon transitoire.</p>
 *
 * <p>{@code dureeEstimeeHeures} est facultatif : sur l'axe Douala → Bangui,
 * certains tronçons ont un délai connu et d'autres pas. Forcer une valeur
 * ferait inventer des chiffres, et un délai inventé est pire qu'absent.</p>
 */
@Entity
@Table(name = "etape_itineraire")
public class EtapeItineraire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "itineraire_id", nullable = false)
    private Itineraire itineraire;

    @Column(name = "lieu_id", nullable = false)
    private Long lieuId;

    @Column(nullable = false)
    private int ordre;

    @Column(name = "duree_estimee_heures")
    private Integer dureeEstimeeHeures;

    protected EtapeItineraire() {
    }

    EtapeItineraire(Itineraire itineraire, Long lieuId, int ordre, Integer dureeEstimeeHeures) {
        this.itineraire = itineraire;
        this.lieuId = lieuId;
        this.ordre = ordre;
        this.dureeEstimeeHeures = dureeEstimeeHeures;
    }

    public Long getId() { return id; }
    public Long getLieuId() { return lieuId; }
    public int getOrdre() { return ordre; }
    public Integer getDureeEstimeeHeures() { return dureeEstimeeHeures; }
}
