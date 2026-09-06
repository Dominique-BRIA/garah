package com.garah.api.catalogue.domaine;

import jakarta.persistence.*;

import java.util.Objects;

/** Une valeur possible d'un {@link Attribut} : « M », « Bleu », « 128 Go ». */
@Entity
@Table(name = "valeur_attribut")
public class ValeurAttribut {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribut_id")
    private Attribut attribut;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 100)
    private String libelle;

    /** Code couleur hexadécimal, pour les attributs affichés en pastille. */
    @Column(name = "valeur_affichage", length = 20)
    private String valeurAffichage;

    @Column(nullable = false)
    private int ordre = 0;

    protected ValeurAttribut() {
    }

    ValeurAttribut(Attribut attribut, String code, String libelle, String valeurAffichage) {
        this.attribut = attribut;
        this.code = code;
        this.libelle = libelle;
        this.valeurAffichage = valeurAffichage;
    }

    public Long getId() { return id; }
    public Attribut getAttribut() { return attribut; }
    public String getCode() { return code; }
    public String getLibelle() { return libelle; }
    public String getValeurAffichage() { return valeurAffichage; }
    public int getOrdre() { return ordre; }

    public void setOrdre(int ordre) { this.ordre = ordre; }

    // Identité par l'id : ces objets vivent dans des Set (voir Variante).
    // Ils viennent toujours de la base, donc leur id est renseigné —
    // contrairement au piège du chapitre 07 §6.
    @Override
    public boolean equals(Object autre) {
        return autre instanceof ValeurAttribut v && id != null && Objects.equals(id, v.id);
    }

    @Override
    public int hashCode() {
        return ValeurAttribut.class.hashCode();
    }
}
