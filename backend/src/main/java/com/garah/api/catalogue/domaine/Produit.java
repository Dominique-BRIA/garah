package com.garah.api.catalogue.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Ce que le client voit dans le catalogue.
 *
 * <p>⚠️ Un produit n'a <b>ni prix ni stock</b> (D-01). Ils vivent sur la
 * {@link Variante}, y compris quand il n'y en a qu'une.</p>
 *
 * <p>{@code marchand_id} est une association vers le domaine marchand, mais
 * mappée en simple {@code Long} : le catalogue n'a pas besoin de charger un
 * marchand pour exister, et ça évite un couplage entre deux domaines
 * (chapitre 06 §4).</p>
 */
@Entity
@Table(name = "produit")
/*
 * 🎯 LA CORBEILLE S'APPLIQUE A TOUTES LES REQUETES, D'UN SEUL COUP.
 *
 * `@SQLRestriction` ajoute cette condition a CHAQUE lecture JPA du produit :
 * les listes, les recherches, `findById`, et les jointures depuis les autres
 * entites. Sans elle, il faudrait ecrire « AND date_suppression IS NULL »
 * dans chacune des requetes du depot — et il suffirait d'en oublier UNE pour
 * qu'un produit supprime reapparaisse quelque part, sans que personne ne
 * sache par ou.
 *
 * ⚠️ DEUX CONSEQUENCES A CONNAITRE :
 *
 *   1. `findById` ne trouve plus un produit en corbeille. La corbeille se lit
 *      donc en SQL natif (`ProduitRepository.corbeille`), qui echappe a cette
 *      restriction. Ce n'est pas un contournement : c'est le seul endroit du
 *      code qui a le droit de voir ces lignes.
 *
 *   2. Elle ne s'applique PAS aux requetes natives deja ecrites. Celle du
 *      stock (`aDejaServi`) interroge ligne_commande et n'est pas concernee ;
 *      toute nouvelle requete native listant des produits devra, elle, poser
 *      la condition a la main.
 */
@org.hibernate.annotations.SQLRestriction("date_suppression IS NULL")
public class Produit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marchand_id", nullable = false)
    private Long marchandId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "categorie_id")
    private CategorieProduit categorie;

    @Column(nullable = false, length = 50)
    private String reference;

    @Column(nullable = false, length = 200)
    private String nom;

    /** Figé à la création : renommer le produit ne doit pas casser les liens. */
    @Column(nullable = false, updatable = false, length = 220)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    /**
     * Attributs libres et non structurants (matière, garantie, origine…).
     *
     * <p>⚠️ Ce qui sert à <b>filtrer ou trier</b> ne doit PAS finir ici : une
     * vraie colonne s'indexe, un champ jsonb noyé dans un document beaucoup
     * moins bien. Le jsonb est fait pour ce qu'on affiche, pas pour ce qu'on
     * interroge.</p>
     */
    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String caracteristiques = "{}";

    @Column(name = "taux_tva", nullable = false, precision = 5, scale = 2)
    private BigDecimal tauxTva = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutProduit statut = StatutProduit.BROUILLON;

    @Column(name = "cree_par")
    private Long creePar;

    @Column(name = "modifie_par")
    private Long modifiePar;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_modification")
    private Instant dateModification;

    /**
     * Non nulle = dans la corbeille (V27).
     *
     * <p>Volontairement <b>distincte du statut</b>. Un brouillon mis à la
     * corbeille reste un brouillon et le redevient tel quel s'il est
     * restauré. Les fondre en un statut {@code SUPPRIME} ferait perdre l'état
     * d'origine, et obligerait à une seconde colonne pour s'en souvenir.</p>
     *
     * <p>Une date plutôt qu'un booléen : elle permet d'afficher « supprimé il
     * y a trois jours » et de purger automatiquement au-delà d'un délai.</p>
     */
    @Column(name = "date_suppression")
    private Instant dateSuppression;

    @OneToMany(mappedBy = "produit", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Variante> variantes = new ArrayList<>();

    @OneToMany(mappedBy = "produit", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Media> medias = new ArrayList<>();

    protected Produit() {
    }

    public Produit(Long marchandId, CategorieProduit categorie, String reference,
                   String nom, Long creePar) {
        this.marchandId = marchandId;
        this.categorie = categorie;
        this.reference = reference;
        this.nom = nom;
        this.slug = Slug.de(nom);
        this.creePar = creePar;
    }

    /**
     * Fixe le slug, une seule fois, à la création.
     *
     * <p>Visible du seul paquet {@code domaine} : c'est {@link ServiceCatalogue}
     * qui l'appelle quand le slug calculé depuis le nom est déjà pris — deux
     * produits peuvent porter le même nom, leur adresse publique non.</p>
     *
     * <p>⚠️ N'en faites pas un moyen de renommer une adresse. Le slug est figé
     * après la création : le changer casserait tous les liens déjà partagés.</p>
     */
    void definirSlug(String slug) {
        this.slug = slug;
    }

    @PreUpdate
    void avantMiseAJour() {
        this.dateModification = Instant.now();
    }

    /**
     * Ajoute une déclinaison.
     *
     * <p>Tout produit en a <b>au moins une</b>, même sans déclinaison réelle :
     * c'est la variante « par défaut » (D-01). Un seul chemin de code, toujours.</p>
     */
    public Variante ajouterVariante(String sku, String libelle, boolean parDefaut) {
        Variante variante = new Variante(this, sku, libelle, parDefaut);
        variantes.add(variante);
        return variante;
    }

    public Media ajouterMedia(TypeMedia type, String cleObjet, boolean principal) {
        Media media = new Media(this, type, cleObjet, principal);
        medias.add(media);
        return media;
    }

    public boolean estPublie() {
        return statut == StatutProduit.PUBLIE;
    }

    /**
     * Change d'état.
     *
     * <p>L'entité ne vérifie que ce qu'elle peut voir : les transitions
     * autorisées. Les conditions de publication (au moins une variante active,
     * un prix, une photo — invariant I-12) demandent des requêtes, donc elles
     * appartiennent au service.</p>
     */
    void changerStatut(StatutProduit nouveau) {
        this.statut = nouveau;
    }

    public Long getId() { return id; }
    public Long getMarchandId() { return marchandId; }
    public CategorieProduit getCategorie() { return categorie; }
    public String getReference() { return reference; }
    public String getNom() { return nom; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public String getCaracteristiques() { return caracteristiques; }
    public BigDecimal getTauxTva() { return tauxTva; }
    public StatutProduit getStatut() { return statut; }
    public Long getCreePar() { return creePar; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateSuppression() { return dateSuppression; }

    /**
     * Met à la corbeille.
     *
     * <p>⚠️ <b>Le statut n'est pas touché.</b> C'est ce qui permet de rendre
     * au produit son état exact à la restauration : un brouillon revient
     * brouillon. Le passer à {@code ARCHIVE} au passage — le réflexe — le
     * rendrait irrécupérable, {@code ARCHIVE} étant terminal.</p>
     */
    public void mettreALaCorbeille() {
        this.dateSuppression = Instant.now();
    }

    /** Sort de la corbeille et retrouve son statut d'avant, intact. */
    public void restaurer() {
        this.dateSuppression = null;
    }

    public boolean estDansLaCorbeille() {
        return dateSuppression != null;
    }
    public List<Variante> getVariantes() { return variantes; }
    public List<Media> getMedias() { return medias; }

    public void setNom(String nom) { this.nom = nom; }
    public void setDescription(String description) { this.description = description; }
    public void setCaracteristiques(String json) { this.caracteristiques = json; }
    public void setTauxTva(BigDecimal taux) { this.tauxTva = taux; }
    public void setCategorie(CategorieProduit categorie) { this.categorie = categorie; }
    public void setMarchandId(Long marchandId) { this.marchandId = marchandId; }
    public void setModifiePar(Long utilisateurId) { this.modifiePar = utilisateurId; }
}
