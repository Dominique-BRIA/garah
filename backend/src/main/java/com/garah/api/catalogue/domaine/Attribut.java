package com.garah.api.catalogue.domaine;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Une dimension de déclinaison : Taille, Couleur, Capacité…
 *
 * <p>Le référentiel des attributs est <b>partagé par tout le catalogue</b> :
 * « Taille » est défini une fois et réutilisé par tous les vêtements. Sans ça,
 * chaque produit inventerait ses propres valeurs et aucun filtre transversal
 * ne serait possible.</p>
 */
@Entity
@Table(name = "attribut")
public class Attribut {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 100)
    private String nom;

    /** LISTE (déroulante) ou PASTILLE (carré de couleur). */
    @Column(name = "type_affichage", nullable = false, length = 20)
    private String typeAffichage = "LISTE";

    @OneToMany(mappedBy = "attribut", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ValeurAttribut> valeurs = new ArrayList<>();

    protected Attribut() {
    }

    public Attribut(String code, String nom, String typeAffichage) {
        this.code = code;
        this.nom = nom;
        this.typeAffichage = typeAffichage;
    }

    public ValeurAttribut ajouterValeur(String code, String libelle, String affichage) {
        ValeurAttribut valeur = new ValeurAttribut(this, code, libelle, affichage);
        valeurs.add(valeur);
        return valeur;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getNom() { return nom; }
    public String getTypeAffichage() { return typeAffichage; }
    public List<ValeurAttribut> getValeurs() { return valeurs; }
}
