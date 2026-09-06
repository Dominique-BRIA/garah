package com.garah.api.config;

import com.garah.api.commun.erreur.ReponseErreur;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Protège du CSRF les deux routes qui s'authentifient par <b>cookie</b>.
 *
 * <h2>Le problème, en une image</h2>
 *
 * <p>Le cookie de rafraîchissement est en {@code SameSite=None} — il le doit,
 * puisque les frontends (Vercel) et l'API (Render) sont deux <b>sites</b>
 * différents (D-14, D-19). Le navigateur l'envoie donc <b>aussi depuis une page
 * tierce</b> :</p>
 *
 * <pre>
 * site-malveillant.example
 *   fetch('https://garah-api.../api/auth/rafraichir',
 *         {method: 'POST', credentials: 'include'})
 *                         ↓
 *   le navigateur joint garah_refresh tout seul
 * </pre>
 *
 * <h2>Pourquoi un en-tête, et pas un jeton CSRF</h2>
 *
 * <p>Le « double-submit cookie » classique consiste à poser un cookie lisible
 * en JavaScript et à exiger sa valeur dans un en-tête. <b>Il ne fonctionne pas
 * ici</b> : un cookie n'est lisible que depuis son propre domaine. Angular,
 * servi par Vercel, ne peut pas lire un cookie posé par Render — il n'aurait
 * jamais rien à renvoyer.</p>
 *
 * <p>On exige donc un <b>en-tête personnalisé</b>. C'est ce qui le rend
 * efficace :</p>
 *
 * <ol>
 *   <li>un en-tête non standard force le navigateur à faire un
 *       <b>préflight</b> {@code OPTIONS} ;</li>
 *   <li>ce préflight est arbitré par CORS, qui n'autorise que les origines de
 *       {@code GARAH_CORS_ORIGINS} ;</li>
 *   <li>une page tierce échoue au préflight — <b>sa requête n'est jamais
 *       envoyée</b>, cookie ou pas.</li>
 * </ol>
 *
 * <p>Un formulaire HTML, lui, ne peut pas poser d'en-tête du tout : c'est
 * précisément le vecteur CSRF historique, et il est fermé d'office.</p>
 *
 * <p>🎯 La sécurité repose donc sur <b>CORS</b>, pas sur le secret de l'en-tête.
 * Sa valeur n'a aucune importance : seule sa <b>présence</b> compte. C'est la
 * défense recommandée par l'OWASP pour une API JSON, et elle a un avantage
 * décisif ici — elle fonctionne cross-origin.</p>
 *
 * <p>⚠️ Elle s'effondre si {@code GARAH_CORS_ORIGINS} contient {@code *}.
 * {@link ConfigurationCors} interdit le joker pour cette raison exacte.</p>
 */
@Component
public class FiltreOrigineCsrf extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltreOrigineCsrf.class);

    /**
     * L'en-tête que le frontend doit poser. Sa valeur est ignorée.
     *
     * <p>En Angular : {@code headers: {'X-Garah-Client': '1'}}, une fois pour
     * toutes dans un intercepteur HTTP.</p>
     */
    public static final String ENTETE = "X-Garah-Client";

    /**
     * Les deux seules routes concernées : celles qui s'authentifient par cookie.
     *
     * <p>Comparaison <b>exacte</b>. Un {@code startsWith("/api/auth")}
     * couvrirait {@code /connexion} et {@code /inscription}, où le frontend
     * n'a pas encore de session — et les fermerait sans raison.</p>
     */
    private static final Set<String> ROUTES_PROTEGEES =
            Set.of("/api/auth/rafraichir", "/api/auth/deconnexion");

    private final ObjectMapper json;

    public FiltreOrigineCsrf(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                    FilterChain suite) throws ServletException, IOException {

        if (ROUTES_PROTEGEES.contains(requete.getRequestURI())
                && requete.getHeader(ENTETE) == null) {

            log.warn("Requete sans en-tete {} sur {} (origine : {}) : refusee.",
                    ENTETE, requete.getRequestURI(),
                    requete.getHeader("Origin") == null ? "aucune" : requete.getHeader("Origin"));

            reponse.setStatus(HttpStatus.FORBIDDEN.value());
            reponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            reponse.setCharacterEncoding("UTF-8");
            json.writeValue(reponse.getWriter(), ReponseErreur.de(
                    "ENTETE_CLIENT_MANQUANT",
                    "Cette requête doit porter l'en-tête " + ENTETE + ".",
                    requete.getRequestURI()));
            return;
        }

        suite.doFilter(requete, reponse);
    }
}
