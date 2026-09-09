package com.garah.api.surveillance.domaine;

import java.time.Instant;
import java.util.Map;

/**
 * Une ligne du parcours d'un client, telle qu'on la montre.
 *
 * <p>⚠️ L'adresse IP n'y figure pas. Elle sert à la <b>surveillance</b> —
 * détecter une anomalie — et pas à comprendre un parcours d'achat. La faire
 * voyager jusqu'à un écran de consultation client, c'est la publier sans que
 * personne l'ait décidé.</p>
 */
public record VueActiviteClient(
        Long id,
        String type,
        Map<String, String> contexte,
        Instant dateHeure) {

    static VueActiviteClient de(ActiviteClient a) {
        return new VueActiviteClient(a.getId(), a.getType().name(),
                a.getContexte(), a.getDateHeure());
    }
}
