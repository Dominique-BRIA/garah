package com.garah.api.sav.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Un retour de marchandise.
 *
 * <p><b>Un retour n'est pas un post-it, c'est une opération</b> — c'était la
 * conclusion de la correction A4. Sa validation déclenche neuf écritures
 * (voir {@code ServiceRetour}), toutes dans la même transaction.</p>
 */
@Entity
@Table(name = "retour")
public class Retour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String numero;

    @Column(name = "commande_id", nullable = false)
    private Long commandeId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** Renseigné quand le retour naît d'une réclamation. */
    @Column(name = "reclamation_id")
    private Long reclamationId;

    @Column(nullable = false, length = 50)
    private String motif;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutRetour statut = StatutRetour.DEMANDE;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_reception")
    private Instant dateReception;

    @OneToMany(mappedBy = "retour", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LigneRetour> lignes = new ArrayList<>();

    protected Retour() {
    }

    public Retour(String numero, Long commandeId, Long clientId, String motif) {
        this.numero = numero;
        this.commandeId = commandeId;
        this.clientId = clientId;
        this.motif = motif;
    }

    public LigneRetour ajouterLigne(Long ligneCommandeId, int quantite, EtatArticle etat) {
        LigneRetour ligne = new LigneRetour(this, ligneCommandeId, quantite, etat);
        lignes.add(ligne);
        return ligne;
    }

    void accepter() {
        this.statut = StatutRetour.ACCEPTE;
    }

    void refuser() {
        this.statut = StatutRetour.REFUSE;
    }

    /**
     * La contrainte {@code retour_reception_coherente} exige une date de
     * réception dès le statut {@code RECEPTIONNE}. Les deux sont posés
     * ensemble, ici et nulle part ailleurs.
     */
    void receptionner() {
        this.statut = StatutRetour.RECEPTIONNE;
        this.dateReception = Instant.now();
    }

    void valider() {
        this.statut = StatutRetour.VALIDE;
    }

    void cloturer() {
        this.statut = StatutRetour.CLOTURE;
    }

    public BigDecimal montantTotalRembourse() {
        return lignes.stream()
                .map(LigneRetour::getMontantRembourse)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Long getId() { return id; }
    public String getNumero() { return numero; }
    public Long getCommandeId() { return commandeId; }
    public Long getClientId() { return clientId; }
    public Long getReclamationId() { return reclamationId; }
    public String getMotif() { return motif; }
    public StatutRetour getStatut() { return statut; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateReception() { return dateReception; }
    public List<LigneRetour> getLignes() { return lignes; }

    public void setReclamationId(Long reclamationId) { this.reclamationId = reclamationId; }
}
