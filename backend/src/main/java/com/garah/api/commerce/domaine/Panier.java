package com.garah.api.commerce.domaine;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Le panier d'un client.
 *
 * <p>Historisé (correction A8) : un client peut avoir plusieurs paniers dans
 * le temps, mais un seul <b>ACTIF</b> — garanti par l'index unique partiel
 * {@code panier_actif_unique}.</p>
 *
 * <p>Les paniers abandonnés sont <b>conservés</b>, parce que la statistique
 * « paniers abandonnés » de la §20 en dépend. Les supprimer reviendrait à
 * effacer la donnée qui mesure la déperdition.</p>
 *
 * <p>⚠️ Un panier ne réserve <b>aucun</b> stock. Mettre un article au panier
 * n'engage rien : c'est la commande qui réserve (chapitre 11). Sinon un
 * visiteur pourrait immobiliser tout le catalogue sans jamais payer.</p>
 */
@Entity
@Table(name = "panier")
public class Panier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, length = 20)
    private String statut = "ACTIF";

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_modification", nullable = false)
    private Instant dateModification = Instant.now();

    @OneToMany(mappedBy = "panier", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LignePanier> lignes = new ArrayList<>();

    protected Panier() {
    }

    public Panier(Long clientId) {
        this.clientId = clientId;
    }

    public Optional<LignePanier> ligneDe(Long varianteId) {
        return lignes.stream()
                .filter(l -> l.getVarianteId().equals(varianteId))
                .findFirst();
    }

    /**
     * Ajoute une variante, ou <b>incrémente</b> si elle y est déjà.
     *
     * <p>La contrainte {@code ligne_panier_unique} interdit deux lignes pour la
     * même variante. Ce n'est pas une gêne : c'est ce qui empêche un panier
     * d'afficher « Chemise M ×2 » et « Chemise M ×3 » sur deux lignes, ce que
     * personne ne sait interpréter.</p>
     */
    public LignePanier ajouter(Long varianteId, int quantite) {
        LignePanier ligne = ligneDe(varianteId)
                .map(existante -> {
                    existante.augmenter(quantite);
                    return existante;
                })
                .orElseGet(() -> {
                    LignePanier nouvelle = new LignePanier(this, varianteId, quantite);
                    lignes.add(nouvelle);
                    return nouvelle;
                });

        this.dateModification = Instant.now();
        return ligne;
    }

    public void retirer(Long varianteId) {
        lignes.removeIf(l -> l.getVarianteId().equals(varianteId));
        this.dateModification = Instant.now();
    }

    public void vider() {
        lignes.clear();
        this.dateModification = Instant.now();
    }

    public boolean estVide() {
        return lignes.isEmpty();
    }

    void convertir() {
        this.statut = "CONVERTI";
        this.dateModification = Instant.now();
    }

    public Long getId() { return id; }
    public Long getClientId() { return clientId; }
    public String getStatut() { return statut; }
    public List<LignePanier> getLignes() { return lignes; }
    public Instant getDateModification() { return dateModification; }
}
