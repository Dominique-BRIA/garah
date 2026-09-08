package com.garah.api.messagerie.domaine;

import java.time.Instant;

/**
 * Un fil, vu par l un des deux.
 *
 * <p>⚠️ Il porte l INTERLOCUTEUR, pas les deux bouts : « responsableA » et
 *    « responsableB » n ont aucun sens a l ecran — on veut savoir a qui l on
 *    parle, et l un des deux est soi.</p>
 */
public record VueFilInterne(
        Long id,
        Long interlocuteurId,
        String interlocuteurNom,
        long nonLus,
        Instant dateDernier) {
}
