package com.garah.api.messagerie.domaine;

import java.time.Instant;

/** Un collegue que j ai bloque, tel que l ecran des reglages l affiche. */
public record VueBlocage(Long responsableId, String nom, String motif,
                         Instant dateBlocage) {
}
