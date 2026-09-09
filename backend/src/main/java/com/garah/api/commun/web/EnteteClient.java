package com.garah.api.commun.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * L'en-tête par lequel une application frontale se nomme.
 *
 * <h2>Il sert à deux choses, et il faut bien les distinguer</h2>
 *
 * <ol>
 *   <li><b>Sa présence</b> protège du CSRF sur les routes qui s'authentifient
 *       par cookie : un en-tête personnalisé force un préflight CORS que seules
 *       nos origines passent. C'est {@code FiltreOrigineCsrf} qui l'exige, et
 *       il ne regarde que la présence.</li>
 *   <li><b>Sa valeur</b> dit de quel public vient l'appel, et décide quel
 *       cookie de session est lu et posé.</li>
 * </ol>
 *
 * <h2>⚠️ La valeur n'est PAS une autorisation</h2>
 *
 * <p>Elle vient du navigateur : n'importe qui peut prétendre être le
 * back-office. Cela ne donne rien — la valeur ne fait que choisir un
 * <b>nom de cookie</b>, c'est-à-dire un espace de rangement. Le jeton qui s'y
 * trouve reste opaque, engendré par le serveur, rattaché à une famille et
 * vérifié en base à chaque usage. Se tromper de tiroir n'a jamais ouvert une
 * porte.</p>
 *
 * <p>Le droit, lui, se lit dans le jeton d'accès, jamais ici.</p>
 */
public final class EnteteClient {

    public static final String NOM = "X-Garah-Client";

    /**
     * La valeur que le back-office envoie.
     *
     * <p>🎯 <b>C'est le back-office qui se déclare, et personne d'autre.</b>
     * Tout le reste — la boutique, l'application mobile, un futur écran —
     * retombe sur le public par défaut. Le sens de ce choix : l'application
     * sensible est celle qu'on <b>isole</b>, et une application qui ne connaît
     * pas cette convention ne peut pas se retrouver dans sa session par
     * accident.</p>
     */
    public static final String BACK_OFFICE = "admin";

    private EnteteClient() {
    }

    /** Vrai si l'appel se déclare venir du back-office. */
    public static boolean estLeBackOffice(HttpServletRequest requete) {
        return BACK_OFFICE.equalsIgnoreCase(requete.getHeader(NOM));
    }
}
