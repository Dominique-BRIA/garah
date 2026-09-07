package com.garah.api.logistique.web;

import com.garah.api.logistique.domaine.TypeLieu;
import com.garah.api.logistique.domaine.VueLieu;
import com.garah.api.logistique.domaine.ServiceLieu;
import com.garah.api.logistique.infra.LieuRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Les lieux : points de récupération et points de transit.
 *
 * <p>⚠️ <b>La liste des points de récupération est publique.</b> Elle doit
 * l'être : le client choisit son point <b>au moment de la commande</b> (D-05),
 * et la vitrine doit pouvoir annoncer « disponible à Douala, Bertoua, Bangui »
 * avant toute inscription. Fermer cette route rendrait le tunnel de vente
 * incompréhensible pour un visiteur.</p>
 *
 * <p>Les points de <b>transit</b>, eux, restent internes : ils décrivent notre
 * chaîne logistique, ce qui ne regarde pas le client.</p>
 */
@RestController
@RequestMapping("/api/lieux")
public class ControleurLieu {

    private static final String ACTIF = "ACTIF";

    private final LieuRepository lieux;
    private final ServiceLieu service;

    public ControleurLieu(LieuRepository lieux, ServiceLieu service) {
        this.lieux = lieux;
        this.service = service;
    }

    /**
     * Les points de récupération <b>actifs</b>, pour le choix au checkout.
     *
     * <p>Seulement les actifs : proposer un point désactivé mènerait à une
     * commande refusée après que le client a tout saisi — le service rejette
     * un point inactif, et il a raison de le faire.</p>
     *
     * <p>Le filtre par ville sert la vitrine : « où puis-je retirer ? » est la
     * première question d'un visiteur, avant même le prix.</p>
     */
    @GetMapping("/points-recuperation")
    public List<VueLieu> pointsRecuperation(@RequestParam(required = false) String ville) {
        var trouves = (ville == null || ville.isBlank())
                ? lieux.findByTypeAndStatutOrderByVilleAscNomAsc(TypeLieu.POINT_RECUPERATION, ACTIF)
                : lieux.findByTypeAndVilleAndStatut(TypeLieu.POINT_RECUPERATION, ville.strip(), ACTIF);

        return trouves.stream().map(VueLieu::de).toList();
    }

    /** Tous les lieux, actifs ou non — back-office. */
    @GetMapping
    @PreAuthorize("hasAuthority('POINT_RECUPERATION_CONSULTER')")
    public List<VueLieu> tous() {
        return lieux.findAll().stream().map(VueLieu::de).toList();
    }

    @GetMapping("/points-transit")
    @PreAuthorize("hasAuthority('POINT_TRANSIT_CONSULTER')")
    public List<VueLieu> pointsTransit() {
        return lieux.findByTypeAndStatutOrderByVilleAscNomAsc(TypeLieu.POINT_TRANSIT, ACTIF)
                .stream()
                .map(VueLieu::de)
                .toList();
    }

    // -------------------------------------------------------------------------
    // L'ecriture
    // -------------------------------------------------------------------------
    //
    // ⚠️ Ces routes MANQUAIENT, et c'etait bloquant : une commande exige un
    // point de recuperation (D-05). Tant qu'aucun n'existe, aucune commande ne
    // peut etre passee — quel que soit l'etat du catalogue.
    //
    // Les permissions different selon le TYPE de lieu, comme le referentiel
    // les a separees : POINT_RECUPERATION_* et POINT_TRANSIT_*. Un point de
    // recuperation est une vitrine, un point de transit est de la logistique
    // interne ; on peut vouloir confier l'un sans l'autre. La verification
    // porte donc sur le corps de la requete, pas sur la route seule.
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('POINT_RECUPERATION_CONSULTER')")
    public VueLieu detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("(#demande.type().name() == 'POINT_RECUPERATION'"
                + "  and hasAuthority('POINT_RECUPERATION_CREER'))"
                + " or (#demande.type().name() != 'POINT_RECUPERATION'"
                + "  and hasAuthority('POINT_TRANSIT_CREER'))")
    public VueLieu creer(@Valid @RequestBody DemandeLieu demande) {
        return service.creer(demande.type(), demande.nom(), demande.pays(), demande.ville(),
                demande.adresse(), demande.telephone(), demande.horaires(),
                demande.fraisAcheminement());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('POINT_RECUPERATION_MODIFIER')"
                + " or hasAuthority('POINT_TRANSIT_MODIFIER')")
    public VueLieu modifier(@PathVariable Long id,
                            @Valid @RequestBody DemandeModificationLieu demande) {
        return service.modifier(id, demande.nom(), demande.pays(), demande.ville(),
                demande.adresse(), demande.telephone(), demande.horaires(),
                demande.fraisAcheminement());
    }

    /*
     * Activation et desactivation par le VERBE, gardees separement : le
     * referentiel distingue ACTIVER de DESACTIVER, et ce n'est pas un exces de
     * zele. Desactiver le dernier point de recuperation ferme le tunnel de
     * vente ; le service le refuse d'ailleurs.
     */
    @PostMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('POINT_RECUPERATION_ACTIVER')"
                + " or hasAuthority('POINT_TRANSIT_ACTIVER')")
    public VueLieu activer(@PathVariable Long id) {
        return service.changerStatut(id, true);
    }

    @DeleteMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('POINT_RECUPERATION_DESACTIVER')"
                + " or hasAuthority('POINT_TRANSIT_DESACTIVER')")
    public VueLieu desactiver(@PathVariable Long id) {
        return service.changerStatut(id, false);
    }

    // -------------------------------------------------------------------------

    public record DemandeLieu(
            @NotNull(message = "Le type de lieu est obligatoire.")
            TypeLieu type,

            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 150, message = "Le nom ne peut pas depasser 150 caracteres.")
            String nom,

            /* Code ISO 3166-1 alpha-2 : CM, CF, TD... Jamais un nom en clair. */
            @NotBlank(message = "Le pays est obligatoire.")
            @Pattern(regexp = "^[A-Za-z]{2}$",
                     message = "Le pays doit etre un code a deux lettres.")
            String pays,

            @NotBlank(message = "La ville est obligatoire.")
            @Size(max = 100, message = "La ville ne peut pas depasser 100 caracteres.")
            String ville,

            @Size(max = 255, message = "Adresse trop longue.")
            String adresse,

            @Size(max = 30, message = "Numero de telephone trop long.")
            @Pattern(regexp = "^$|^[+()0-9 .-]{6,30}$",
                     message = "Ce numero de telephone n'est pas valide.")
            String telephone,

            @Size(max = 255, message = "Horaires trop longs.")
            String horaires,

            /*
             * Ce que coute l'acheminement jusqu'a CE point. Fige sur la
             * commande (D-11) : le changer ensuite ne touche aucune commande
             * deja passee. N'a de sens que sur un point de recuperation.
             */
            @DecimalMin(value = "0", message = "Les frais ne peuvent pas etre negatifs.")
            @Digits(integer = 13, fraction = 2, message = "Montant mal forme.")
            BigDecimal fraisAcheminement) {
    }

    /** Le type n'y figure pas : voir {@code ServiceLieu.modifier}. */
    public record DemandeModificationLieu(
            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 150, message = "Le nom ne peut pas depasser 150 caracteres.")
            String nom,

            @NotBlank(message = "Le pays est obligatoire.")
            @Pattern(regexp = "^[A-Za-z]{2}$",
                     message = "Le pays doit etre un code a deux lettres.")
            String pays,

            @NotBlank(message = "La ville est obligatoire.")
            @Size(max = 100, message = "La ville ne peut pas depasser 100 caracteres.")
            String ville,

            @Size(max = 255, message = "Adresse trop longue.")
            String adresse,

            @Size(max = 30, message = "Numero de telephone trop long.")
            @Pattern(regexp = "^$|^[+()0-9 .-]{6,30}$",
                     message = "Ce numero de telephone n'est pas valide.")
            String telephone,

            @Size(max = 255, message = "Horaires trop longs.")
            String horaires,

            @DecimalMin(value = "0", message = "Les frais ne peuvent pas etre negatifs.")
            @Digits(integer = 13, fraction = 2, message = "Montant mal forme.")
            BigDecimal fraisAcheminement) {
    }
}
