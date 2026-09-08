package com.garah.api.messagerie.domaine;

/**
 * Un collegue a qui l on peut ecrire.
 *
 * <p>⚠️ Ceux qui m ont BLOQUE n y figurent pas, ni les inactifs. Les laisser
 *    ferait choisir un destinataire pour se voir refuser l envoi juste apres —
 *    un refus qu on pouvait eviter avant le clic.</p>
 */
public record VueCollegue(Long responsableId, String nom) {
}
