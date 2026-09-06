package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Le profil métier d'un responsable — et son titre affiché.
 *
 * <p>Une catégorie ne CRÉE pas de droits : elle en SÉLECTIONNE parmi ceux du
 * catalogue {@link CasUtilisation}. C'est ce qui empêche un Admin d'inventer
 * une permission qui ne correspond à aucun code (chapitre 01 §4).</p>
 */
@Entity
@Table(name = "categorie_responsable")
public class CategorieResponsable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nom;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 20)
    private String statut = "ACTIF";

    /**
     * Les cas d'utilisation accordés par ce profil.
     *
     * <p>Table d'association simple, sans attribut propre : {@code @ManyToMany}
     * convient. Dès qu'une table de liaison porte une colonne supplémentaire
     * (comme {@code responsable_cas_utilisation} et son {@code type}), il faut
     * en faire une vraie entité.</p>
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "categorie_cas_utilisation",
            joinColumns = @JoinColumn(name = "categorie_id"),
            inverseJoinColumns = @JoinColumn(name = "cas_utilisation_id"))
    private Set<CasUtilisation> casUtilisation = new LinkedHashSet<>();

    protected CategorieResponsable() {
    }

    public CategorieResponsable(String nom) {
        this.nom = nom;
    }

    public void accorder(CasUtilisation cas) {
        casUtilisation.add(cas);
    }

    public void retirer(CasUtilisation cas) {
        casUtilisation.remove(cas);
    }

    public Long getId() { return id; }
    public String getNom() { return nom; }
    public String getDescription() { return description; }
    public String getStatut() { return statut; }
    public Set<CasUtilisation> getCasUtilisation() { return casUtilisation; }

    public void setDescription(String description) { this.description = description; }
    public void setStatut(String statut) { this.statut = statut; }
}
