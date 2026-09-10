package com.garah.api.messagerie.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un fil entre DEUX responsables.
 *
 * <h2>⚠️ La paire est ORDONNÉE, et ce n'est pas une convention d'écriture</h2>
 *
 * <p>⚠️ Les deux bouts référencent {@code utilisateur}, et non {@code responsable} :
 * la messagerie est ouverte à TOUT compte interne, administration comprise
 * (V32). Elle ne l'était pas — non par décision, mais parce que le schéma
 * d'origine pointait vers une table où un ADMIN n'a pas de ligne.</p>
 *
 * <p>{@code utilisateurA} porte toujours le plus petit identifiant. Sans cela,
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

    @Column(name = "utilisateur_a", nullable = false)
    private Long utilisateurA;

    @Column(name = "utilisateur_b", nullable = false)
    private Long utilisateurB;

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
    public FilInterne(Long unCompte, Long autreCompte) {
        this.utilisateurA = Math.min(unCompte, autreCompte);
        this.utilisateurB = Math.max(unCompte, autreCompte);
    }

    public void toucher() {
        this.dateDernier = Instant.now();
    }

    public boolean concerne(Long utilisateurId) {
        return utilisateurA.equals(utilisateurId) || utilisateurB.equals(utilisateurId);
    }

    /** L'autre bout du fil, vu de {@code utilisateurId}. */
    public Long interlocuteurDe(Long utilisateurId) {
        return utilisateurA.equals(utilisateurId) ? utilisateurB : utilisateurA;
    }

    public Long getId() { return id; }
    public Long getUtilisateurA() { return utilisateurA; }
    public Long getUtilisateurB() { return utilisateurB; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateDernier() { return dateDernier; }
}
