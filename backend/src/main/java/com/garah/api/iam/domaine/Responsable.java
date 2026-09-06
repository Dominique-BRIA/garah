package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Un opérateur de l'entreprise.
 *
 * <p>Ses capacités ne sont pas les mêmes que celles de ses collègues : elles
 * découlent de ses catégories (D-02) et de ses exceptions individuelles.
 * Le calcul est fait par {@code ServicePermissions}, pas ici — parce qu'il
 * demande une requête, et qu'une entité ne doit pas savoir requêter.</p>
 */
@Entity
@Table(name = "responsable")
public class Responsable {

    @Id
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id")
    private Utilisateur utilisateur;

    @Column(nullable = false, length = 20)
    private String matricule;

    @Column(name = "date_embauche")
    private LocalDate dateEmbauche;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutUtilisateur statut = StatutUtilisateur.ACTIF;

    /**
     * Les rattachements aux catégories.
     *
     * <p>{@code LAZY} volontairement : afficher une liste de 200 responsables
     * ne doit pas charger leurs catégories. Le jour où on en a besoin, on
     * écrit une requête qui les charge explicitement — c'est le sujet du
     * problème N+1 (chapitre 07 §6).</p>
     */
    @OneToMany(mappedBy = "responsable", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ResponsableCategorie> categories = new LinkedHashSet<>();

    protected Responsable() {
    }

    public Responsable(Utilisateur utilisateur, String matricule) {
        this.utilisateur = utilisateur;
        this.matricule = matricule;
    }

    /**
     * Rattache le responsable à une catégorie.
     *
     * <p>⚠️ L'unicité de la catégorie principale (I-02) est garantie par un
     * index unique partiel en base. Ce code ne la revérifie pas : il la
     * respecte. La base reste la dernière ligne de défense.</p>
     */
    public void ajouterCategorie(CategorieResponsable categorie, boolean principale) {
        categories.add(new ResponsableCategorie(this, categorie, principale));
    }

    /**
     * Le titre affiché du responsable : le nom de sa catégorie principale.
     *
     * <p>Il n'existe volontairement aucun champ {@code titre} : il finirait
     * par diverger du profil réel (spec §6).</p>
     */
    public Optional<String> titre() {
        return categories.stream()
                .filter(ResponsableCategorie::estPrincipale)
                .map(rc -> rc.getCategorie().getNom())
                .findFirst();
    }

    public Long getId() { return id; }
    public Utilisateur getUtilisateur() { return utilisateur; }
    public String getMatricule() { return matricule; }
    public LocalDate getDateEmbauche() { return dateEmbauche; }
    public StatutUtilisateur getStatut() { return statut; }
    public Set<ResponsableCategorie> getCategories() { return categories; }

    public void setDateEmbauche(LocalDate date) { this.dateEmbauche = date; }
    public void setStatut(StatutUtilisateur statut) { this.statut = statut; }
}
