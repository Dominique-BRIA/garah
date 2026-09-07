package com.garah.api.logistique.domaine;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.logistique.infra.ItineraireRepository;
import com.garah.api.logistique.infra.LieuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Les trajets types de la chaîne : Douala → Bertoua → Garoua-Boulaï → Bangui.
 *
 * <h2>Un modèle, pas une contrainte</h2>
 *
 * <p>⚠️ Un itinéraire décrit ce qui est <b>prévu</b>. Le trajet réel du colis
 * vit dans ses événements, et {@code ServiceExpedition.enregistrer} ne vérifie
 * <b>pas</b> qu'un scan a lieu sur l'itinéraire annoncé — c'est délibéré et
 * documenté là-bas : une route coupée, un déroutement, ça arrive, et une
 * contrainte qui empêche d'enregistrer la réalité pousse l'opérateur à saisir
 * n'importe quoi d'autre.</p>
 *
 * <p>Ce service sert donc à deux choses, pas trois : ne pas resaisir le même
 * trajet à chaque expédition, et annoncer un délai.</p>
 */
@Service
public class ServiceItineraire {

    private final ItineraireRepository itineraires;
    private final LieuRepository lieux;

    public ServiceItineraire(ItineraireRepository itineraires, LieuRepository lieux) {
        this.itineraires = itineraires;
        this.lieux = lieux;
    }

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------

    /**
     * La liste complète, lieux nommés.
     *
     * <p>Deux requêtes en tout, quel que soit le nombre d'itinéraires : une
     * pour les itinéraires et leurs étapes, une pour tous les lieux cités.</p>
     */
    @Transactional(readOnly = true)
    public List<VueItineraire> lister(boolean actifsSeulement) {
        List<Itineraire> trouves = itineraires.listerAvecEtapes().stream()
                .filter(i -> !actifsSeulement || i.estActif())
                .sorted((a, b) -> a.getNom().compareToIgnoreCase(b.getNom()))
                .toList();

        Map<Long, Lieu> nommes = chargerLieux(trouves);
        return trouves.stream().map(i -> vue(i, nommes)).toList();
    }

    @Transactional(readOnly = true)
    public VueItineraire detail(Long id) {
        Itineraire itineraire = charger(id);
        return vue(itineraire, chargerLieux(List.of(itineraire)));
    }

    // -------------------------------------------------------------------------
    // Écriture
    // -------------------------------------------------------------------------

    @Transactional
    public VueItineraire creer(String nom, Long lieuDepartId, Long lieuArriveeId,
                               List<Itineraire.EtapeSouhaitee> etapes) {
        verifierTrajet(lieuDepartId, lieuArriveeId);

        Itineraire itineraire = new Itineraire(nom.trim(), lieuDepartId, lieuArriveeId);
        poserEtapes(itineraire, etapes, lieuDepartId, lieuArriveeId);

        return detailApresEcriture(itineraires.save(itineraire));
    }

    /**
     * Modifie un itinéraire, étapes comprises.
     *
     * <p>Les étapes sont <b>remplacées en bloc</b>, jamais modifiées une par
     * une. Réordonner rang par rang traverserait forcément un état où deux
     * étapes portent le même rang, et la contrainte I-34 refuse cet état même
     * transitoire.</p>
     *
     * <p>D'où le {@code flush} entre la suppression et la réinsertion : sans
     * lui, Hibernate est libre d'ordonner ses écritures comme il veut, et rien
     * ne garantit que les {@code DELETE} partent avant les {@code INSERT}.
     * Le symptôme serait une violation d'unicité qui n'apparaît que sur
     * certaines modifications — celles qui réutilisent un rang libéré.</p>
     */
    @Transactional
    public VueItineraire modifier(Long id, String nom, Long lieuDepartId, Long lieuArriveeId,
                                  List<Itineraire.EtapeSouhaitee> etapes) {
        Itineraire itineraire = charger(id);
        verifierTrajet(lieuDepartId, lieuArriveeId);

        itineraire.renommer(nom.trim());
        itineraire.changerTrajet(lieuDepartId, lieuArriveeId);

        itineraire.getEtapes().clear();
        itineraires.saveAndFlush(itineraire);

        poserEtapes(itineraire, etapes, lieuDepartId, lieuArriveeId);
        return detailApresEcriture(itineraires.save(itineraire));
    }

    /**
     * Active ou désactive.
     *
     * <p>Désactiver, jamais supprimer : des expéditions passées référencent cet
     * itinéraire, et l'effacer réécrirait l'histoire de trajets qui ont bien eu
     * lieu.</p>
     */
    @Transactional
    public VueItineraire activer(Long id, boolean actif) {
        Itineraire itineraire = charger(id);
        itineraire.activer(actif);
        return detailApresEcriture(itineraire);
    }

    // -------------------------------------------------------------------------

    /**
     * Les extrémités doivent exister, différer, et arriver quelque part de
     * sensé.
     *
     * <p>L'arrivée est forcément un point de récupération : c'est la
     * définition du trajet dans GARAH, qui ne livre pas à domicile (D-05). Le
     * départ, lui, peut être un entrepôt comme un point de transit — une
     * marchandise consolidée à Bertoua repart de là.</p>
     */
    private void verifierTrajet(Long lieuDepartId, Long lieuArriveeId) {
        Lieu depart = chargerLieu(lieuDepartId);
        Lieu arrivee = chargerLieu(lieuArriveeId);

        if (depart.getId().equals(arrivee.getId())) {
            throw new RegleMetierViolee("TRAJET_INVALIDE",
                    "Le départ et l'arrivée ne peuvent pas être le même lieu.");
        }
        if (arrivee.getType() != TypeLieu.POINT_RECUPERATION) {
            throw new RegleMetierViolee("ARRIVEE_INVALIDE",
                    "L'arrivée doit être un point de récupération.");
        }
        if (depart.getType() == TypeLieu.POINT_RECUPERATION) {
            throw new RegleMetierViolee("DEPART_INVALIDE",
                    "Un trajet part d'un entrepôt ou d'un point de transit.");
        }
    }

    /**
     * Pose les étapes intermédiaires après les avoir vérifiées.
     *
     * <p>Trois refus, et chacun protège d'un itinéraire qui se lirait mal :</p>
     *
     * <ul>
     *   <li>une étape qui répète le départ ou l'arrivée — ils sont déjà là,
     *       et le trajet afficherait deux fois la même ville ;</li>
     *   <li>deux fois le même lieu — un colis ne repasse pas au même endroit
     *       dans un trajet <b>prévu</b> (dans le trajet réel, si, et c'est
     *       justement pour ça que les événements ne sont pas contraints) ;</li>
     *   <li>une durée nulle ou négative, que la base refuserait de toute façon
     *       — mais avec un message que personne ne peut lire.</li>
     * </ul>
     */
    private void poserEtapes(Itineraire itineraire, List<Itineraire.EtapeSouhaitee> etapes,
                             Long lieuDepartId, Long lieuArriveeId) {
        List<Itineraire.EtapeSouhaitee> propres = new ArrayList<>();
        Set<Long> deja = new HashSet<>();

        for (Itineraire.EtapeSouhaitee etape : etapes) {
            Lieu lieu = chargerLieu(etape.lieuId());

            if (lieu.getId().equals(lieuDepartId) || lieu.getId().equals(lieuArriveeId)) {
                throw new RegleMetierViolee("ETAPE_REDONDANTE",
                        "« " + lieu.getNom() + " » est déjà le départ ou l'arrivée.");
            }
            if (!deja.add(lieu.getId())) {
                throw new RegleMetierViolee("ETAPE_EN_DOUBLE",
                        "« " + lieu.getNom() + " » apparaît deux fois dans le trajet.");
            }
            if (etape.dureeEstimeeHeures() != null && etape.dureeEstimeeHeures() <= 0) {
                throw new RegleMetierViolee("DUREE_INVALIDE",
                        "Une durée estimée se compte en heures pleines, au moins une.");
            }
            propres.add(etape);
        }

        itineraire.remplacerEtapes(propres);
    }

    private VueItineraire detailApresEcriture(Itineraire itineraire) {
        return vue(itineraire, chargerLieux(List.of(itineraire)));
    }

    /** Tous les lieux cités par ces itinéraires, en une requête. */
    private Map<Long, Lieu> chargerLieux(List<Itineraire> trouves) {
        Set<Long> ids = new HashSet<>();
        for (Itineraire i : trouves) {
            ids.add(i.getLieuDepartId());
            ids.add(i.getLieuArriveeId());
            i.getEtapes().forEach(e -> ids.add(e.getLieuId()));
        }
        return lieux.findAllById(ids).stream()
                .collect(Collectors.toMap(Lieu::getId, l -> l));
    }

    private VueItineraire vue(Itineraire i, Map<Long, Lieu> nommes) {
        return new VueItineraire(
                i.getId(), i.getNom(),
                i.getLieuDepartId(), nom(nommes, i.getLieuDepartId()),
                i.getLieuArriveeId(), nom(nommes, i.getLieuArriveeId()),
                i.getStatut(), i.dureeTotaleHeures(),
                i.getEtapes().stream()
                        .map(e -> {
                            Lieu lieu = nommes.get(e.getLieuId());
                            return new VueItineraire.VueEtape(
                                    e.getId(), e.getLieuId(),
                                    lieu == null ? null : lieu.getNom(),
                                    lieu == null ? null : lieu.getVille(),
                                    e.getOrdre(), e.getDureeEstimeeHeures());
                        })
                        .toList());
    }

    /** Nul si le lieu a disparu : l'itinéraire reste lisible sans lui. */
    private static String nom(Map<Long, Lieu> nommes, Long lieuId) {
        Lieu lieu = nommes.get(lieuId);
        return lieu == null ? null : lieu.getNom();
    }

    private Itineraire charger(Long id) {
        return itineraires.chargerAvecEtapes(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Itineraire", id));
    }

    private Lieu chargerLieu(Long id) {
        return lieux.findById(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Lieu", id));
    }
}
