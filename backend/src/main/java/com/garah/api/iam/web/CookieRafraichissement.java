package com.garah.api.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Le cookie qui porte le jeton de rafraîchissement.
 *
 * <p>🎯 <b>Pourquoi un cookie plutôt que le corps JSON.</b> Le jeton de
 * rafraîchissement vaut quatorze jours d'accès au compte. Rangé dans
 * {@code localStorage}, il est lisible par n'importe quel JavaScript de la
 * page — donc par la moindre faille XSS, y compris dans une dépendance npm.
 * En cookie {@code HttpOnly}, il est <b>hors de portée du JavaScript</b> : un
 * XSS ne peut plus l'exfiltrer.</p>
 *
 * <p>Le jeton d'<b>accès</b>, lui, reste en Bearer dans le JSON, et le frontend
 * le garde <b>en mémoire</b> — pas dans {@code localStorage}. Il dure 15
 * minutes et disparaît au rechargement de l'onglet, où le cookie le
 * régénère.</p>
 *
 * <pre>
 * accès              15 min   en mémoire JS       volé = 15 min de dégâts
 * rafraîchissement   14 j     cookie HttpOnly     JS ne peut pas le lire
 * </pre>
 */
@Component
public class CookieRafraichissement {

    /** Le nom du cookie. Préfixé pour ne jamais entrer en collision. */
    public static final String NOM = "garah_refresh";

    /**
     * ⚠️ <b>Le chemin restreint est une protection, pas un détail.</b>
     *
     * <p>Le navigateur n'envoie ce cookie que vers {@code /api/auth/*}. Les
     * quatre-vingt-dix autres routes de l'API ne le voient <b>jamais</b> :
     * elles ne peuvent donc ni le journaliser par accident, ni le renvoyer
     * dans un message d'erreur, ni le transmettre à un tiers.</p>
     */
    private static final String CHEMIN = "/api/auth";

    private final boolean securise;
    private final String memeSite;
    private final String domaine;

    public CookieRafraichissement(
            @Value("${GARAH_COOKIE_SECURE:true}") boolean securise,
            @Value("${GARAH_COOKIE_SAMESITE:None}") String memeSite,
            @Value("${GARAH_COOKIE_DOMAIN:}") String domaine) {

        this.securise = securise;
        this.memeSite = memeSite == null || memeSite.isBlank() ? "None" : memeSite.strip();
        this.domaine = domaine == null ? "" : domaine.strip();
    }

    /**
     * Pose le cookie.
     *
     * <h2>⚠️ Sur {@code SameSite}, et pourquoi le défaut est {@code None}</h2>
     *
     * <p>{@code Strict} serait le réglage le plus sûr, et c'est celui qu'on
     * voudrait. Il est <b>inutilisable dans l'hébergement retenu</b> (D-14) :</p>
     *
     * <pre>
     * frontends   garah-client.vercel.app
     * API         garah-api.onrender.com     ← autre site, pas seulement autre origine
     * </pre>
     *
     * <p>Pour le navigateur, {@code vercel.app} et {@code onrender.com} sont
     * deux <b>sites</b> différents. Avec {@code Strict} ou même {@code Lax}, le
     * cookie ne serait <b>jamais</b> envoyé : le rafraîchissement échouerait
     * systématiquement, et le symptôme serait une déconnexion toutes les 15
     * minutes sans la moindre erreur serveur.</p>
     *
     * <p>{@code None} est donc obligatoire ici — et il impose {@code Secure},
     * ce que les navigateurs vérifient. La contrepartie est que le cookie
     * <b>est</b> envoyé sur les requêtes inter-sites, d'où la protection CSRF
     * réactivée sur ces routes (voir {@code ConfigurationSecurite}).</p>
     *
     * <p>🎯 <b>Le jour du VPS, ou d'un domaine propre</b> ({@code api.garah.cm}
     * et {@code app.garah.cm} partagent {@code garah.cm}), passer
     * {@code GARAH_COOKIE_SAMESITE=Lax} : la protection CSRF devient alors
     * structurelle plutôt que dépendante d'un jeton. C'est une raison de plus
     * de prendre un vrai domaine tôt.</p>
     */
    public String poser(String jeton, long dureeSecondes) {
        return construire(jeton, Duration.ofSeconds(dureeSecondes)).toString();
    }

    /**
     * Efface le cookie, à la déconnexion.
     *
     * <p>⚠️ Les attributs {@code Path}, {@code Domain}, {@code Secure} et
     * {@code SameSite} doivent être <b>identiques</b> à la pose, sinon le
     * navigateur considère qu'il s'agit d'un autre cookie et laisse l'ancien
     * en place. L'utilisateur croirait s'être déconnecté sans l'être — d'où le
     * passage par la même méthode de construction.</p>
     */
    public String effacer() {
        return construire("", Duration.ZERO).toString();
    }

    /** Lit le cookie dans la requête entrante. */
    public Optional<String> lire(HttpServletRequest requete) {
        if (requete.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(requete.getCookies())
                .filter(c -> NOM.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /** L'en-tête à poser sur la réponse. */
    public static String enTete() {
        return HttpHeaders.SET_COOKIE;
    }

    private ResponseCookie construire(String valeur, Duration duree) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(NOM, valeur)
                // Hors de portée de tout JavaScript : c'est la raison d'être
                // de ce choix face à localStorage.
                .httpOnly(true)
                // Obligatoire dès que SameSite vaut None, et de toute façon
                // souhaitable : sans lui, le jeton voyagerait en clair.
                .secure(securise)
                .sameSite(memeSite)
                .path(CHEMIN)
                .maxAge(duree);

        if (!domaine.isBlank()) {
            cookie.domain(domaine);
        }
        return cookie.build();
    }
}
