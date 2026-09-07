package com.garah.api.logistique.web;

import com.garah.api.logistique.domaine.Itineraire;
import com.garah.api.logistique.domaine.ServiceItineraire;
import com.garah.api.logistique.domaine.VueItineraire;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Les trajets types : Douala → Bertoua → Garoua-Boulaï → Bangui.
 *
 * <p>⚠️ Un itinéraire est un <b>modèle</b>, pas une contrainte. Le trajet réel
 * du colis vit dans ses événements et peut s'en écarter — voir
 * {@code ServiceItineraire}.</p>
 *
 * <h2>Pourquoi les étapes n'ont pas leurs propres routes</h2>
 *
 * <p>Le référentiel des permissions distingue « ajouter une étape », « la
 * modifier », « la supprimer » et « les réordonner ». On aurait donc pu ouvrir
 * quatre routes de plus.</p>
 *
 * <p>Ce serait une erreur : réordonner un itinéraire rang par rang traverse
 * forcément un état où deux étapes portent le même rang, et la contrainte I-34
 * refuse cet état <b>même transitoire</b>. Chaque appel réussirait ou
 * échouerait selon l'ordre des clics, ce qui est exactement le genre de bogue
 * qu'on ne reproduit jamais.</p>
 *
 * <p>Le trajet se soumet donc <b>en entier</b>, et le service le remplace en
 * bloc. {@code ITINERAIRE_MODIFIER} garde ce geste — les quatre permissions
 * d'étape restent disponibles pour une interface plus fine le jour où elle
 * aura un sens.</p>
 */
@RestController
@RequestMapping("/api/itineraires")
public class ControleurItineraire {

    private final ServiceItineraire itineraires;

    public ControleurItineraire(ServiceItineraire itineraires) {
        this.itineraires = itineraires;
    }

    /**
     * @param actifs {@code true} pour ne garder que les trajets utilisables.
     *               Le back-office veut tout voir, y compris les trajets
     *               retirés ; un formulaire d'expédition ne veut que les
     *               actifs.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ITINERAIRE_CONSULTER')")
    public List<VueItineraire> lister(@RequestParam(defaultValue = "false") boolean actifs) {
        return itineraires.lister(actifs);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ITINERAIRE_CONSULTER')")
    public VueItineraire detail(@PathVariable Long id) {
        return itineraires.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ITINERAIRE_CREER')")
    public VueItineraire creer(@Valid @RequestBody DemandeItineraire demande) {
        return itineraires.creer(demande.nom(), demande.lieuDepartId(),
                demande.lieuArriveeId(), demande.etapes());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ITINERAIRE_MODIFIER')")
    public VueItineraire modifier(@PathVariable Long id,
                                  @Valid @RequestBody DemandeItineraire demande) {
        return itineraires.modifier(id, demande.nom(), demande.lieuDepartId(),
                demande.lieuArriveeId(), demande.etapes());
    }

    /**
     * Désactive ou réactive — il n'y a pas de suppression.
     *
     * <p>Des expéditions passées référencent cet itinéraire. L'effacer
     * réécrirait l'histoire de trajets qui ont bien eu lieu. C'est la règle
     * du projet : désactiver, jamais supprimer.</p>
     */
    @PutMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('ITINERAIRE_MODIFIER')")
    public VueItineraire activer(@PathVariable Long id,
                                 @Valid @RequestBody DemandeActivation demande) {
        return itineraires.activer(id, demande.actif());
    }

    // -------------------------------------------------------------------------

    /**
     * Le trajet <b>en entier</b>, extrémités et étapes.
     *
     * <p>{@code etapes} peut être vide : Douala → Bangui direct est un trajet
     * légitime.</p>
     */
    public record DemandeItineraire(
            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 150, message = "Nom trop long.")
            String nom,

            @NotNull(message = "Le lieu de départ est obligatoire.") Long lieuDepartId,
            @NotNull(message = "Le lieu d'arrivée est obligatoire.") Long lieuArriveeId,

            @Size(max = 20, message = "Un trajet ne compte pas plus de vingt étapes.")
            List<Itineraire.EtapeSouhaitee> etapes) {

        public DemandeItineraire {
            // Un corps sans « etapes » vaut « aucune étape », pas une erreur :
            // le cas le plus simple ne doit pas demander un champ vide.
            etapes = etapes == null ? List.of() : etapes;
        }
    }

    public record DemandeActivation(
            @NotNull(message = "L'état est obligatoire.") Boolean actif) {
    }
}
