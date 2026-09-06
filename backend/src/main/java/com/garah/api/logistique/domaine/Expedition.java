package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Un envoi physique rattaché à une commande.
 *
 * <p>Une commande peut donner <b>plusieurs</b> expéditions : marchandises de
 * marchands différents, ou colis partant à des dates différentes. C'est
 * pourquoi la relation n'est pas un simple 1-1 (§15 de la spécification).</p>
 *
 * <p>L'itinéraire est <b>facultatif</b> (correction A9) : une livraison locale
 * n'en a pas. Et quand il existe, ce n'est qu'un <b>modèle prévu</b> — le
 * trajet réel vit dans les événements et peut s'en écarter.</p>
 */
@Entity
@Table(name = "expedition")
public class Expedition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String numero;

    @Column(name = "commande_id", nullable = false)
    private Long commandeId;

    @Column(name = "marchand_id")
    private Long marchandId;

    @Column(name = "itineraire_id")
    private Long itineraireId;

    @Column(name = "lieu_depart_id", nullable = false)
    private Long lieuDepartId;

    @Column(name = "point_recuperation_id", nullable = false)
    private Long pointRecuperationId;

    /** Colonne technique qui rend la clé étrangère composite possible (I-25). */
    @Column(name = "point_recuperation_type", nullable = false, length = 20)
    private String pointRecuperationType = "POINT_RECUPERATION";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutExpedition statut = StatutExpedition.CREEE;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_expedition")
    private Instant dateExpedition;

    @Column(name = "date_arrivee_prevue")
    private Instant dateArriveePrevue;

    @OneToMany(mappedBy = "expedition", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Colis> colis = new ArrayList<>();

    protected Expedition() {
    }

    public Expedition(String numero, Long commandeId, Long lieuDepartId, Long pointRecuperationId) {
        this.numero = numero;
        this.commandeId = commandeId;
        this.lieuDepartId = lieuDepartId;
        this.pointRecuperationId = pointRecuperationId;
    }

    public Colis ajouterColis(String numeroSuivi) {
        Colis nouveau = new Colis(this, numeroSuivi);
        colis.add(nouveau);
        return nouveau;
    }

    /**
     * Recalcule le statut à partir de celui des colis.
     *
     * <p>Une expédition va au rythme de son colis le <b>moins avancé</b> :
     * elle n'est « disponible » que lorsque tout est arrivé. Annoncer au
     * client que sa commande est prête alors qu'un colis est encore en route
     * est la meilleure façon de le faire venir pour rien.</p>
     */
    void projeterDepuisLesColis() {
        if (colis.isEmpty()) {
            return;
        }
        if (colis.stream().anyMatch(c -> c.getStatut() == StatutColis.BLOQUE)) {
            this.statut = StatutExpedition.BLOQUEE;
        } else if (colis.stream().allMatch(c -> c.getStatut() == StatutColis.REMIS)) {
            this.statut = StatutExpedition.REMISE;
        } else if (colis.stream().allMatch(c -> c.getStatut() == StatutColis.DISPONIBLE
                                             || c.getStatut() == StatutColis.REMIS)) {
            this.statut = StatutExpedition.DISPONIBLE;
        } else if (colis.stream().anyMatch(c -> c.getStatut() == StatutColis.EN_TRANSIT)) {
            this.statut = StatutExpedition.EN_TRANSIT;
        }
    }

    void marquerExpediee() {
        this.dateExpedition = Instant.now();
        this.statut = StatutExpedition.EN_TRANSIT;
    }

    public Long getId() { return id; }
    public String getNumero() { return numero; }
    public Long getCommandeId() { return commandeId; }
    public Long getMarchandId() { return marchandId; }
    public Long getItineraireId() { return itineraireId; }
    public Long getLieuDepartId() { return lieuDepartId; }
    public Long getPointRecuperationId() { return pointRecuperationId; }
    public StatutExpedition getStatut() { return statut; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateExpedition() { return dateExpedition; }
    public List<Colis> getColis() { return colis; }

    public void setMarchandId(Long marchandId) { this.marchandId = marchandId; }
    public void setItineraireId(Long itineraireId) { this.itineraireId = itineraireId; }
    public void setDateArriveePrevue(Instant date) { this.dateArriveePrevue = date; }
}
