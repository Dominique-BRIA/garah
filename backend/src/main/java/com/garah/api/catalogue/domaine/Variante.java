package com.garah.api.catalogue.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * La référence réellement vendable : c'est elle qu'on met au panier.
 *
 * <p>Elle porte le SKU, le poids, le stock et les prix. Un produit sans
 * déclinaison réelle a quand même une variante, marquée {@code parDefaut}.</p>
 *
 * <p>⚠️ {@code parDefaut} sert à l'<b>affichage</b> (quelle variante montrer
 * en premier), <b>jamais</b> à choisir un chemin de code. Écrire
 * {@code if (variante.estParDefaut())} pour sauter une étape ramènerait
 * exactement le double chemin que D-01 cherche à éviter.</p>
 */
@Entity
@Table(name = "variante")
public class Variante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produit_id")
    private Produit produit;

    @Column(nullable = false, length = 60)
    private String sku;

    @Column(length = 200)
    private String libelle;

    @Column(name = "poids_kg", precision = 10, scale = 3)
    private BigDecimal poidsKg;

    @Column(name = "par_defaut", nullable = false)
    private boolean parDefaut = false;

    @Column(nullable = false, length = 20)
    private String statut = "ACTIVE";

    /**
     * Les valeurs d'attribut qui définissent cette déclinaison (M, Bleu…).
     *
     * <p>Table de liaison <b>sans attribut propre</b> : un {@code @ManyToMany}
     * suffit. Dès qu'une colonne s'y ajoute, il faudra en faire une entité —
     * comme {@code responsable_categorie} au chapitre 07.</p>
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "variante_attribut",
            joinColumns = @JoinColumn(name = "variante_id"),
            inverseJoinColumns = @JoinColumn(name = "valeur_attribut_id"))
    private Set<ValeurAttribut> valeurs = new LinkedHashSet<>();

    protected Variante() {
    }

    Variante(Produit produit, String sku, String libelle, boolean parDefaut) {
        this.produit = produit;
        this.sku = sku;
        this.libelle = libelle;
        this.parDefaut = parDefaut;
    }

    public void definirPar(ValeurAttribut valeur) {
        valeurs.add(valeur);
    }

    public boolean estActive() {
        return "ACTIVE".equals(statut);
    }

    public Long getId() { return id; }
    public Produit getProduit() { return produit; }
    public String getSku() { return sku; }
    public String getLibelle() { return libelle; }
    public BigDecimal getPoidsKg() { return poidsKg; }
    public boolean estParDefaut() { return parDefaut; }
    public String getStatut() { return statut; }
    public Set<ValeurAttribut> getValeurs() { return valeurs; }

    public void setSku(String sku) { this.sku = sku; }
    public void setLibelle(String libelle) { this.libelle = libelle; }
    public void setPoidsKg(BigDecimal poids) { this.poidsKg = poids; }
    public void setStatut(String statut) { this.statut = statut; }
}
