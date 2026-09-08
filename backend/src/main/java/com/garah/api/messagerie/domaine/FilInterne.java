package com.garah.api.messagerie.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un fil entre DEUX responsables.
 *
 * <h2>⚠️ La paire est ORDONNÉE, et ce n'est pas une convention d'écriture</h2>
 *
 * <p>{@code responsableA} porte toujours le plus petit identifiant. Sans cela,
 * A→B et B→A donneraient <b>deux fils</b> pour la même discussion : chacun
 * verrait la moitié des messages et croirait l'autre muet.</p>
 *
 * <p>La règle est tenue par un {@code CHECK} et un index unique en base, pas
 * seulement ici : un appel qui l'oublierait un jour serait refusé plutôt que
 * de créer le doublon.</p>
 */
@Entity
@Table(name = "fil_interne")
public class FilInterne {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "responsable_a", nullable = false)
    private Long responsableA;

    @Column(name = "responsable_b", nullable = false)
    private Long responsableB;

    @Column(name = "date_creation", nullable = false)
    private Instant dateCreation = Instant.now();

    /**
     * La date du dernier message.
     *
     * <p>Recopiée ici plutot que calculée : « mes fils, le plus recent
     * d'abord » est la seule question posee a cette table, et la calculer
     * demanderait de joindre tous les messages a chaque affichage.</p>
     */
    @Column(name = "date_dernier", nullable = false)
    private Instant dateDernier = Instant.now();

    protected FilInterne() {
    }

    /** Range les deux identifiants dans l'ordre, quelle que soit la saisie. */
    public FilInterne(Long unResponsable, Long autreResponsable) {
        this.responsableA = Math.min(unResponsable, autreResponsable);
        this.responsableB = Math.max(unResponsable, autreResponsable);
    }

    public void toucher() {
        this.dateDernier = Instant.now();
    }

    public boolean concerne(Long responsableId) {
        return responsableA.equals(responsableId) || responsableB.equals(responsableId);
    }

    /** L'autre bout du fil, vu de {@code responsableId}. */
    public Long interlocuteurDe(Long responsableId) {
        return responsableA.equals(responsableId) ? responsableB : responsableA;
    }

    public Long getId() { return id; }
    public Long getResponsableA() { return responsableA; }
    public Long getResponsableB() { return responsableB; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateDernier() { return dateDernier; }
}
