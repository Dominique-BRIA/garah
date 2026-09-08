package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Le rattachement d'un responsable à une catégorie (D-02).
 *
 * <p>Table de liaison avec un attribut propre ({@code principale}) : ce n'est
 * donc pas un {@code @ManyToMany}, mais une <b>vraie entité</b> avec une clé
 * composite.</p>
 *
 * <p><b>Le motif de la clé composite</b>, à connaître car il revient partout :
 * {@code @EmbeddedId} porte les deux colonnes, et chaque {@code @ManyToOne}
 * est marqué {@code @MapsId} pour dire « cette association REMPLIT ce morceau
 * de la clé ». Sans {@code @MapsId}, JPA écrirait les colonnes deux fois et
 * refuserait de démarrer.</p>
 */
@Entity
@Table(name = "responsable_categorie")
public class ResponsableCategorie {

    @Embeddable
    public static class Cle implements Serializable {

        @Column(name = "responsable_id")
        private Long responsableId;

        @Column(name = "categorie_id")
        private Long categorieId;

        protected Cle() {
        }

        public Cle(Long responsableId, Long categorieId) {
            this.responsableId = responsableId;
            this.categorieId = categorieId;
        }

        public Long getResponsableId() { return responsableId; }
        public Long getCategorieId() { return categorieId; }

        @Override
        public boolean equals(Object autre) {
            return autre instanceof Cle c
                    && Objects.equals(responsableId, c.responsableId)
                    && Objects.equals(categorieId, c.categorieId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(responsableId, categorieId);
        }
    }

    @EmbeddedId
    private Cle cle = new Cle();

    @MapsId("responsableId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "responsable_id")
    private Responsable responsable;

    @MapsId("categorieId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "categorie_id")
    private CategorieResponsable categorie;

    /** Une seule à {@code true} par responsable — garanti par un index unique partiel (I-02). */
    @Column(nullable = false)
    private boolean principale = false;

    /**
     * Ce responsable <b>dirige</b> ce service.
     *
     * <p>⚠️ Un seul à {@code true} par <b>service</b> — garanti par un index
     * unique partiel (V31). Sans lui, deux chefs pourraient se désactiver l'un
     * l'autre, et on ne saurait pas lequel a raison.</p>
     *
     * <p>Le chef est nécessairement membre du service : c'est la même ligne qui
     * porte l'appartenance et la direction. Un chef qui ne serait pas membre
     * serait un supérieur sans équipe.</p>
     */
    @Column(nullable = false)
    private boolean chef = false;

    @Column(name = "date_affectation", nullable = false, updatable = false)
    private Instant dateAffectation = Instant.now();

    protected ResponsableCategorie() {
    }

    ResponsableCategorie(Responsable responsable, CategorieResponsable categorie, boolean principale) {
        this.responsable = responsable;
        this.categorie = categorie;
        this.principale = principale;
    }

    public Cle getCle() { return cle; }
    public Responsable getResponsable() { return responsable; }
    public CategorieResponsable getCategorie() { return categorie; }
    public boolean estPrincipale() { return principale; }
    public boolean estChef() { return chef; }

    /**
     * Nomme ou démet le chef de ce service.
     *
     * <p>Le contrôle de <b>qui a le droit</b> de le faire n'est pas ici : il
     * vit dans le service, seul à connaître l'appelant. Une entité qui
     * vérifierait des permissions aurait besoin de savoir qui la manipule, et
     * ne pourrait plus être construite dans un test.</p>
     */
    void nommerChef(boolean chef) {
        this.chef = chef;
    }

    /**
     * ⚠️ <b>Ne PAS fonder equals/hashCode sur la clé composite.</b>
     *
     * <p>Au moment du {@code new}, {@code cle} est encore vide : c'est
     * {@code @MapsId} qui la remplira au moment du {@code flush}. Deux
     * rattachements fraîchement construits auraient donc une clé
     * {@code (null, null)} — donc ils seraient <b>égaux</b>, et le second
     * serait <b>silencieusement avalé</b> par le {@code Set} de
     * {@link Responsable}.</p>
     *
     * <p>Le symptôme est traître : aucune erreur, aucune exception. Simplement
     * un responsable qui n'a qu'une catégorie sur les deux, et des permissions
     * manquantes que personne ne comprend.</p>
     *
     * <p>On compare donc les <b>associations</b>, qui sont renseignées dès la
     * construction.</p>
     */
    @Override
    public boolean equals(Object autre) {
        return autre instanceof ResponsableCategorie rc
                && Objects.equals(responsable, rc.responsable)
                && Objects.equals(categorie, rc.categorie);
    }

    @Override
    public int hashCode() {
        return Objects.hash(responsable, categorie);
    }
}
