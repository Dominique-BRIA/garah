package com.garah.api.logistique.domaine;

import java.util.List;

/**
 * Un itinéraire tel qu'un écran l'affiche.
 *
 * <p>Les lieux y sont <b>nommés</b>, pas numérotés. Une liste d'itinéraires
 * qui afficherait « 3 → 7 → 12 » obligerait à ouvrir chaque ligne pour savoir
 * de quel trajet il s'agit — et « quel trajet ? » est justement la question
 * qu'on se pose en la lisant.</p>
 *
 * @param dureeTotaleHeures {@code null} dès qu'une seule étape ne connaît pas
 *                          sa durée. Un total partiel annoncerait un délai que
 *                          personne ne tiendrait.
 */
public record VueItineraire(
        Long id,
        String nom,
        Long lieuDepartId,
        String lieuDepart,
        Long lieuArriveeId,
        String lieuArrivee,
        String statut,
        Integer dureeTotaleHeures,
        List<VueEtape> etapes) {

    public record VueEtape(
            Long id,
            Long lieuId,
            String lieu,
            String ville,
            int ordre,
            Integer dureeEstimeeHeures) {
    }
}
