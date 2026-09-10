package com.garah.api.messagerie.domaine;

import java.time.Instant;

/** Un collegue que j ai bloque, tel que l ecran des reglages l affiche. */
/**
 * ⚠️ Le champ s appelait `responsableId`. Depuis V32 un blocage peut viser
 * TOUT compte interne, administration comprise : le nom designait un type
 * d acteur alors qu il en accepte quatre.
 */
public record VueBlocage(Long utilisateurId, String nom, String motif,
                         Instant dateBlocage) {
}
