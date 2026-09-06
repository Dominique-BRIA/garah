package com.garah.api.marchand.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un marchand : GARAH elle-même, ou un partenaire qui vend via la plateforme.
 *
 * <p>La table existait depuis V3, sans entité JPA : les tests l'alimentaient
 * par {@code INSERT} direct, et aucune interface ne pouvait en créer un.
 * Comme un produit exige un marchand, <b>le catalogue entier était
 * inatteignable</b> depuis le back-office.</p>
 *
 * <p>La distinction {@code INTERNE} / {@code EXTERNE} n'est pas décorative :
 * elle décide de la commission. Une vente d'un marchand interne n'engendre
 * aucune dette envers un tiers ; celle d'un partenaire, si — c'est ce que le
 * grand livre du chapitre 17 enregistre.</p>
 */
@Entity
@Table(name = "marchand")
public class Marchand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Le code lisible, dicté au téléphone et lu sur un bordereau.
     *
     * <p>Unique, et <b>jamais renvoyé à zéro</b> : il sert de référence dans
     * les échanges avec le partenaire, bien après que la ligne a changé.</p>
     */
    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TypeMarchand type;

    @Column(length = 30)
    private String telephone;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutMarchand statut = StatutMarchand.ACTIF;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_modification")
    private Instant dateModification;

    protected Marchand() {
    }

    public Marchand(String code, String nom, TypeMarchand type) {
        this.code = code;
        this.nom = nom;
        this.type = type;
    }

    @PreUpdate
    void avantMiseAJour() {
        this.dateModification = Instant.now();
    }

    public boolean estActif() {
        return statut == StatutMarchand.ACTIF;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getNom() { return nom; }
    public TypeMarchand getType() { return type; }
    public String getTelephone() { return telephone; }
    public String getEmail() { return email; }
    public StatutMarchand getStatut() { return statut; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateModification() { return dateModification; }

    public void setNom(String nom) { this.nom = nom; }
    public void setTelephone(String telephone) { this.telephone = telephone; }
    public void setEmail(String email) { this.email = email; }
    public void setStatut(StatutMarchand statut) { this.statut = statut; }

    /**
     * ⚠️ Ni {@code code} ni {@code type} n'ont de mutateur.
     *
     * <p>Le code est une référence partagée avec le partenaire ; le type
     * décide de la commission et donc du grand livre. Les changer après coup
     * rendrait incohérentes des écritures comptables déjà passées.</p>
     */
}
