package com.garah.api.commerce.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Un engagement d'achat.
 *
 * <p>Tous les montants sont <b>TTC</b> (D-11). La contrainte
 * {@code commande_total_coherent} rend impossible un total qui ne correspond
 * pas à ses composantes :</p>
 *
 * <pre>montant_total = montant_articles + montant_frais − montant_remise</pre>
 *
 * <p>C'est la contrainte qui protège le plus d'argent pour le moins d'effort :
 * aucun bug de calcul, aucun script d'import, aucune correction manuelle ne
 * peut écrire un total faux.</p>
 */
@Entity
@Table(name = "commande")
public class Commande {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String numero;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** Renseigné quand la commande naît d'une négociation (chapitre 14). */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** Choisi par le client à la commande (D-05). C'est une photo, pas une préférence. */
    @Column(name = "point_recuperation_id", nullable = false)
    private Long pointRecuperationId;

    /**
     * Colonne technique qui rend la clé étrangère composite possible.
     *
     * <p>Elle vaut toujours {@code POINT_RECUPERATION} et la base l'impose.
     * Couplée à {@code UNIQUE (id, type)} sur {@code lieu}, elle garantit que
     * le lieu choisi <b>est</b> un point de récupération — sans trigger
     * (chapitre 05 §4.3).</p>
     */
    @Column(name = "point_recuperation_type", nullable = false, length = 20)
    private String pointRecuperationType = "POINT_RECUPERATION";

    /** PHOTO : la langue d'émission du document (D-09). */
    @Column(nullable = false, length = 2)
    private String langue = "fr";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StatutCommande statut = StatutCommande.EN_ATTENTE_PAIEMENT;

    @Column(name = "montant_articles", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantArticles = BigDecimal.ZERO;

    @Column(name = "montant_frais", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantFrais = BigDecimal.ZERO;

    @Column(name = "montant_remise", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantRemise = BigDecimal.ZERO;

    @Column(name = "montant_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantTotal = BigDecimal.ZERO;

    @Column(name = "montant_tva", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantTva = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String devise = "XAF";

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_modification")
    private Instant dateModification;

    @OneToMany(mappedBy = "commande", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LigneCommande> lignes = new ArrayList<>();

    protected Commande() {
    }

    public Commande(String numero, Long clientId, Long pointRecuperationId, String langue) {
        this.numero = numero;
        this.clientId = clientId;
        this.pointRecuperationId = pointRecuperationId;
        this.langue = langue;
    }

    @PreUpdate
    void avantMiseAJour() {
        this.dateModification = Instant.now();
    }

    public void ajouterLigne(LigneCommande ligne) {
        lignes.add(ligne);
    }

    /**
     * Recalcule les totaux à partir des lignes.
     *
     * <p>Appelé <b>après</b> avoir ajouté toutes les lignes et fixé les frais.
     * Les montants ne sont jamais saisis : ils sont toujours dérivés — sinon
     * on pourrait écrire un total qui ne correspond à rien, et la contrainte
     * le refuserait au dernier moment sans qu'on comprenne pourquoi.</p>
     */
    public void recalculer() {
        this.montantArticles = lignes.stream()
                .map(LigneCommande::getMontantLigne)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.montantTva = lignes.stream()
                .map(LigneCommande::getMontantTva)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.montantTotal = montantArticles.add(montantFrais).subtract(montantRemise);
    }

    void changerStatut(StatutCommande nouveau) {
        this.statut = nouveau;
    }

    public Long getId() { return id; }
    public String getNumero() { return numero; }
    public Long getClientId() { return clientId; }
    public Long getConversationId() { return conversationId; }
    public Long getPointRecuperationId() { return pointRecuperationId; }
    public String getLangue() { return langue; }
    public StatutCommande getStatut() { return statut; }
    public BigDecimal getMontantArticles() { return montantArticles; }
    public BigDecimal getMontantFrais() { return montantFrais; }
    public BigDecimal getMontantRemise() { return montantRemise; }
    public BigDecimal getMontantTotal() { return montantTotal; }
    public BigDecimal getMontantTva() { return montantTva; }
    public String getDevise() { return devise; }
    public Instant getDateCreation() { return dateCreation; }
    public List<LigneCommande> getLignes() { return lignes; }

    public void setMontantFrais(BigDecimal frais) { this.montantFrais = frais; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
}
