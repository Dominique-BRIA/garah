package com.garah.api.iam.web;

import com.garah.api.commun.web.EnteteClient;
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

    /**
     * ⚠️ <b>UN NOM PAR PUBLIC, et c'est ce qui sépare les deux sessions.</b>
     *
     * <p>Le navigateur range un cookie sous la clé
     * <i>(domaine, chemin, nom)</i>. Un nom unique voulait donc dire <b>une
     * session par navigateur</b>, partagée entre la boutique et le
     * back-office : se connecter en client d'un côté remplaçait la session
     * d'administration de l'autre, sans un mot (D-33).</p>
     *
     * <p>Deux noms, deux rangements, deux sessions qui coexistent.</p>
     *
     * <p>⚠️ Ce n'est <b>pas</b> une frontière de sécurité. Le public est
     * déclaré par un en-tête que le navigateur envoie : n'importe qui peut
     * prétendre être le back-office. Cela ne donne rien — le jeton rangé là
     * reste opaque, engendré par le serveur, rattaché à une famille et vérifié
     * en base. Se tromper de tiroir n'ouvre aucune porte. Ce que ces deux noms
     * empêchent, c'est un <b>écrasement accidentel</b>, pas une intrusion.</p>
     */
    private static final String NOM_BACK_OFFICE = "garah_refresh_admin";

    /**
     * Le nom pour tout le reste : boutique, mobile, et ce qui viendra.
     *
     * <p>🎯 C'est le back-office qui se déclare, et personne d'autre. Une
     * application qui ignore cette convention ne peut donc pas atterrir dans
     * sa session par accident — le sens du choix est d'isoler la sensible.</p>
     */
    private static final String NOM_BOUTIQUE = "garah_refresh_boutique";

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
    public String poser(HttpServletRequest requete, String jeton, long dureeSecondes) {
        return construire(nomPour(requete), jeton, Duration.ofSeconds(dureeSecondes)).toString();
    }

    /**
     * Le nom du cookie pour l'appelant.
     *
     * <p>Le back-office se déclare par l'en-tête client ; tout le reste tombe
     * sur le nom de la boutique.</p>
     */
    private static String nomPour(HttpServletRequest requete) {
        return EnteteClient.estLeBackOffice(requete) ? NOM_BACK_OFFICE : NOM_BOUTIQUE;
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
    public String effacer(HttpServletRequest requete) {
        return construire(nomPour(requete), "", Duration.ZERO).toString();
    }

    /**
     * Lit le cookie de l'appelant — <b>le sien seulement</b>.
     *
     * <p>⚠️ Un navigateur peut porter les deux à la fois. Chercher « le
     * premier cookie de rafraîchissement trouvé » rendrait la session de
     * l'autre public, et on retomberait exactement sur le défaut qu'on
     * répare.</p>
     */
    public Optional<String> lire(HttpServletRequest requete) {
        if (requete.getCookies() == null) {
            return Optional.empty();
        }
        String nom = nomPour(requete);
        return Arrays.stream(requete.getCookies())
                .filter(c -> nom.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /** L'en-tête à poser sur la réponse. */
    public static String enTete() {
        return HttpHeaders.SET_COOKIE;
    }

    private ResponseCookie construire(String nom, String valeur, Duration duree) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(nom, valeur)
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
