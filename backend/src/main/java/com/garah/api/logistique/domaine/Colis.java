package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Un paquet physique, suivi individuellement.
 *
 * <p>Son {@code statut} est une <b>projection</b> du dernier événement, pas
 * une saisie. Il existe pour l'affichage et pour les requêtes de liste ;
 * la vérité reste la suite des événements (règle fondatrice n°1).</p>
 */
@Entity
@Table(name = "colis")
public class Colis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expedition_id")
    private Expedition expedition;

    @Column(name = "numero_suivi", nullable = false, length = 40)
    private String numeroSuivi;

    @Column(name = "poids_kg", precision = 10, scale = 3)
    private BigDecimal poidsKg;

    @Column(length = 50)
    private String dimensions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutColis statut = StatutColis.CREE;

    @OneToMany(mappedBy = "colis", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LigneColis> lignes = new ArrayList<>();

    protected Colis() {
    }

    Colis(Expedition expedition, String numeroSuivi) {
        this.expedition = expedition;
        this.numeroSuivi = numeroSuivi;
    }

    public LigneColis ajouterLigne(Long ligneCommandeId, int quantite) {
        LigneColis ligne = new LigneColis(this, ligneCommandeId, quantite);
        lignes.add(ligne);
        return ligne;
    }

    /**
     * Le statut correspondant à un type d'événement.
     *
     * <p><b>Toute la projection tient dans cette méthode.</b> C'est
     * volontaire : un seul endroit à lire pour savoir comment un événement se
     * traduit en statut, et un seul endroit à corriger.</p>
     *
     * <p>{@code CONTROLE} ne change rien — contrôler un colis ne le fait pas
     * avancer. {@code RECEPTION} et {@code ARRIVEE} non plus : c'est le lieu
     * qui décide, et le service le sait mieux que l'entité.</p>
     */
    static StatutColis projeter(TypeEvenement type, boolean auPointDeRecuperation) {
        return switch (type) {
            case DEPART -> StatutColis.EN_TRANSIT;
            case ARRIVEE, RECEPTION -> auPointDeRecuperation
                    ? StatutColis.DISPONIBLE
                    : StatutColis.EN_TRANSIT;
            case ANOMALIE -> StatutColis.BLOQUE;
            case REMISE -> StatutColis.REMIS;
            case CONTROLE -> null;   // n'affecte pas le statut
        };
    }

    void appliquer(TypeEvenement type, boolean auPointDeRecuperation) {
        StatutColis nouveau = projeter(type, auPointDeRecuperation);
        if (nouveau != null) {
            this.statut = nouveau;
        }
    }

    public Long getId() { return id; }
    public Expedition getExpedition() { return expedition; }
    public String getNumeroSuivi() { return numeroSuivi; }
    public BigDecimal getPoidsKg() { return poidsKg; }
    public StatutColis getStatut() { return statut; }
    public List<LigneColis> getLignes() { return lignes; }

    public void setPoidsKg(BigDecimal poids) { this.poidsKg = poids; }
    public void setDimensions(String dimensions) { this.dimensions = dimensions; }
}
