package com.garah.api.commun.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * L'adresse IP réelle du visiteur, derrière un ou plusieurs proxys.
 *
 * <p><b>Pourquoi cette classe existe.</b> Derrière Render, Vercel ou un Nginx,
 * {@code getRemoteAddr()} renvoie l'adresse du <b>proxy</b>, jamais celle du
 * visiteur. Sans lecture de {@code X-Forwarded-For}, tous les événements de
 * sécurité porteraient la même adresse — et le score de risque, qui compte les
 * adresses distinctes d'un client, serait rigoureusement aveugle.</p>
 *
 * <p>Le calcul était écrit dans {@code ControleurAuthentification}. Il est
 * remonté ici parce qu'un deuxième appelant est arrivé (le paiement) : une
 * règle de sécurité dupliquée est une règle qui divergera.</p>
 *
 * <p>⚠️ <b>Cet en-tête est fourni par le client et se falsifie trivialement.</b>
 * Il est fiable uniquement parce que le proxy de tête le réécrit. Ne jamais
 * fonder une <b>autorisation</b> dessus : il sert à la surveillance et au
 * diagnostic, pas à décider qui a le droit de faire quoi.</p>
 */
public final class AdresseClient {

    private AdresseClient() {
    }

    public static String de(HttpServletRequest requete) {
        String transmise = requete.getHeader("X-Forwarded-For");
        if (transmise != null && !transmise.isBlank()) {
            // La liste va du client d'origine au dernier proxy : le premier
            // élément est celui qui nous intéresse.
            String premiere = transmise.split(",")[0].trim();
            if (!premiere.isBlank()) {
                return premiere;
            }
        }
        return requete.getRemoteAddr();
    }
}
