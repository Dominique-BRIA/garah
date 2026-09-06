package com.garah.api.logistique.web;

import com.garah.api.logistique.domaine.TypeLieu;
import com.garah.api.logistique.domaine.VueLieu;
import com.garah.api.logistique.infra.LieuRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public ControleurLieu(LieuRepository lieux) {
        this.lieux = lieux;
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
}
