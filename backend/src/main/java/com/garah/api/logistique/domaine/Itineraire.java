package com.garah.api.logistique.domaine;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Un <b>modèle</b> de trajet, réutilisable d'une expédition à l'autre.
 *
 * <h2>Ce que ce n'est PAS</h2>
 *
 * <p>⚠️ Ce n'est pas le trajet réel du colis. Le trajet réel vit dans les
 * événements ({@link EvenementExpedition}), et il peut s'écarter du modèle :
 * une route coupée, un déroutement, un colis qui repart en arrière — ça
 * arrive.</p>
 *
 * <p>C'est la même distinction qu'entre un statut et un événement, appliquée
 * au trajet : <b>l'itinéraire est le plan, les événements sont les faits.</b>
 * Confondre les deux mènerait à refuser d'enregistrer la réalité parce qu'elle
 * ne colle pas au plan — et l'opérateur saisirait alors n'importe quoi
 * d'autre.</p>
 *
 * <h2>À quoi il sert, alors</h2>
 *
 * <p>À ne pas resaisir « Douala → Bertoua → Garoua-Boulaï → Bangui » à chaque
 * expédition, et à annoncer un délai. Une expédition référence un itinéraire
 * de façon <b>facultative</b> : une expédition directe n'en a pas.</p>
 */
@Entity
@Table(name = "itineraire")
public class Itineraire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nom;

    @Column(name = "lieu_depart_id", nullable = false)
    private Long lieuDepartId;

    @Column(name = "lieu_arrivee_id", nullable = false)
    private Long lieuArriveeId;

    @Column(nullable = false, length = 20)
    private String statut = "ACTIF";

    /**
     * Les étapes intermédiaires, dans l'ordre.
     *
     * <p>{@code orphanRemoval} : une étape retirée de la liste est
     * <b>supprimée</b>, pas laissée orpheline avec un itinéraire nul. Sans ça,
     * réordonner un itinéraire laisserait des lignes fantômes que plus rien
     * n'affiche mais que la contrainte d'unicité sur (itinéraire, ordre)
     * continuerait à voir.</p>
     */
    @OneToMany(mappedBy = "itineraire", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordre ASC")
    private List<EtapeItineraire> etapes = new ArrayList<>();

    protected Itineraire() {
    }

    public Itineraire(String nom, Long lieuDepartId, Long lieuArriveeId) {
        this.nom = nom;
        this.lieuDepartId = lieuDepartId;
        this.lieuArriveeId = lieuArriveeId;
    }

    /**
     * Remplace toutes les étapes d'un coup.
     *
     * <p>🎯 <b>Remplacer, et non ajouter une par une.</b> Réordonner un
     * itinéraire en modifiant les rangs un par un traverse forcément un état
     * où deux étapes portent le même rang — et la contrainte
     * {@code etape_itineraire_ordre_unique} (I-34) refuse cet état
     * intermédiaire, même s'il devait disparaître à la ligne suivante.</p>
     *
     * <p>On vide donc, on <b>vide vraiment</b> (le flush est fait par le
     * service), puis on renumérote de 1 à n. Les rangs sont engendrés ici :
     * les faire saisir laisserait des trous et des doublons.</p>
     */
    public void remplacerEtapes(List<EtapeSouhaitee> souhaitees) {
        etapes.clear();
        int rang = 1;
        for (EtapeSouhaitee s : souhaitees) {
            etapes.add(new EtapeItineraire(this, s.lieuId(), rang++, s.dureeEstimeeHeures()));
        }
    }

    /** Une étape telle qu'on la demande : sans rang, il est engendré. */
    public record EtapeSouhaitee(Long lieuId, Integer dureeEstimeeHeures) {
    }

    /**
     * La durée annoncée, ou {@code null} si une seule étape ne la connaît pas.
     *
     * <p>Un total partiel serait pire que pas de total : « 12 h » pour un
     * trajet dont deux tronçons sur cinq sont chiffrés annoncerait un délai
     * que personne ne tiendrait.</p>
     */
    public Integer dureeTotaleHeures() {
        int total = 0;
        for (EtapeItineraire etape : etapes) {
            if (etape.getDureeEstimeeHeures() == null) {
                return null;
            }
            total += etape.getDureeEstimeeHeures();
        }
        return etapes.isEmpty() ? null : total;
    }

    public void renommer(String nom) {
        this.nom = nom;
    }

    public void changerTrajet(Long lieuDepartId, Long lieuArriveeId) {
        this.lieuDepartId = lieuDepartId;
        this.lieuArriveeId = lieuArriveeId;
    }

    /** Désactiver, jamais supprimer : des expéditions passées le référencent. */
    public void activer(boolean actif) {
        this.statut = actif ? "ACTIF" : "INACTIF";
    }

    public boolean estActif() {
        return "ACTIF".equals(statut);
    }

    public Long getId() { return id; }
    public String getNom() { return nom; }
    public Long getLieuDepartId() { return lieuDepartId; }
    public Long getLieuArriveeId() { return lieuArriveeId; }
    public String getStatut() { return statut; }
    public List<EtapeItineraire> getEtapes() { return etapes; }
}
