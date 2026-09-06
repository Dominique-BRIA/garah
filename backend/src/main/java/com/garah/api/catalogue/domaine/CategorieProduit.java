package com.garah.api.catalogue.domaine;

import jakarta.persistence.*;

/**
 * Une catégorie du catalogue, organisée en arbre (correction A17).
 *
 * <p>Le parent est une association vers la même entité : « Téléphones » a pour
 * parent « Électronique ». La base interdit qu'une catégorie soit son propre
 * parent, mais <b>pas</b> les cycles plus longs (A → B → A) : SQL ne sait pas
 * l'exprimer, c'est au service de le vérifier.</p>
 */
@Entity
@Table(name = "categorie_produit")
public class CategorieProduit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private CategorieProduit parent;

    @Column(nullable = false, length = 150)
    private String nom;

    @Column(nullable = false, length = 150)
    private String slug;

    @Column(nullable = false)
    private int ordre = 0;

    @Column(nullable = false, length = 20)
    private String statut = "ACTIVE";

    protected CategorieProduit() {
    }

    public CategorieProduit(String nom, CategorieProduit parent) {
        this.nom = nom;
        this.slug = Slug.de(nom);
        this.parent = parent;
    }

    public Long getId() { return id; }
    public CategorieProduit getParent() { return parent; }
    public String getNom() { return nom; }
    public String getSlug() { return slug; }
    public int getOrdre() { return ordre; }
    public String getStatut() { return statut; }

    public void setOrdre(int ordre) { this.ordre = ordre; }
    public void setStatut(String statut) { this.statut = statut; }
}
