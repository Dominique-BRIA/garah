package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * Un lieu du réseau logistique : entrepôt, point de transit ou point de
 * récupération.
 *
 * <p>Les frais d'acheminement portés ici sont <b>copiés</b> dans la commande
 * au moment de l'achat (D-10) : le tarif de Bangui passera de 8 000 à 9 500,
 * et les commandes passées doivent continuer à afficher 8 000.</p>
 */
@Entity
@Table(name = "lieu")
public class Lieu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeLieu type;

    @Column(nullable = false, length = 150)
    private String nom;

    @Column(nullable = false, length = 80)
    private String pays;

    @Column(nullable = false, length = 100)
    private String ville;

    @Column(columnDefinition = "text")
    private String adresse;

    @Column(length = 30)
    private String telephone;

    @Column(length = 200)
    private String horaires;

    @Column(name = "frais_acheminement", nullable = false, precision = 15, scale = 2)
    private BigDecimal fraisAcheminement = BigDecimal.ZERO;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(nullable = false, length = 20)
    private String statut = "ACTIF";

    protected Lieu() {
    }

    public Lieu(TypeLieu type, String nom, String pays, String ville) {
        this.type = type;
        this.nom = nom;
        this.pays = pays;
        this.ville = ville;
    }

    public boolean estActif() {
        return "ACTIF".equals(statut);
    }

    public Long getId() { return id; }
    public TypeLieu getType() { return type; }
    public String getNom() { return nom; }
    public String getPays() { return pays; }
    public String getVille() { return ville; }
    public String getAdresse() { return adresse; }
    public String getTelephone() { return telephone; }
    public String getHoraires() { return horaires; }
    public BigDecimal getFraisAcheminement() { return fraisAcheminement; }
    public String getStatut() { return statut; }

    /**
     * Corrige l'identité du lieu.
     *
     * <p>Une méthode nommée plutôt que trois {@code set} : nom, pays et ville
     * se lisent ensemble — « Agence Bertoua, CM, Bertoua » — et les changer
     * séparément permettrait d'écrire une ville camerounaise sous un pays
     * centrafricain sans que rien ne s'y oppose.</p>
     *
     * <p>⚠️ Le {@code type} n'en fait pas partie et n'a aucun {@code set} :
     * un point de récupération devenu point de transit laisserait derrière lui
     * des commandes dont le point de retrait n'en est plus un.</p>
     */
    public void renommer(String nom, String pays, String ville) {
        this.nom = nom;
        this.pays = pays;
        this.ville = ville;
    }

    public void setAdresse(String adresse) { this.adresse = adresse; }
    public void setTelephone(String telephone) { this.telephone = telephone; }
    public void setHoraires(String horaires) { this.horaires = horaires; }
    public void setFraisAcheminement(BigDecimal frais) { this.fraisAcheminement = frais; }
    public void setStatut(String statut) { this.statut = statut; }
}
