package com.garah.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Autorise les trois frontends à appeler l'API.
 *
 * <p>GARAH a trois applications Angular servies depuis trois origines
 * différentes (D-03, D-14). Sans configuration CORS, le navigateur bloque
 * chacun de leurs appels : l'API répond correctement, mais le navigateur
 * refuse de livrer la réponse au JavaScript.</p>
 *
 * <p>Les origines viennent d'une variable d'environnement : en local ce sont
 * trois {@code localhost}, en production trois domaines Vercel, et le jour du
 * VPS trois autres. Aucune n'est écrite dans le code.</p>
 */
@Configuration
public class ConfigurationCors {

    @Value("${GARAH_CORS_ORIGINS:http://localhost:4200}")
    private String origines;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Jamais "*" : l'API portera des cookies et des jetons. On liste
        // explicitement qui a le droit d'appeler.
        configuration.setAllowedOrigins(List.of(origines.split("\\s*,\\s*")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 🎯 CETTE LISTE EST LA PROTECTION CSRF, pas une formalite.
        //
        // FiltreOrigineCsrf exige l'en-tete X-Garah-Client sur les deux routes
        // qui s'authentifient par cookie. Un en-tete non standard force le
        // navigateur a faire un PREFLIGHT, arbitre par les origines ci-dessus :
        // une page tierce echoue au preflight et sa requete n'est jamais
        // envoyee, cookie ou pas (D-19).
        //
        // ⚠️ Deux consequences a ne pas perdre de vue :
        //    - retirer cet en-tete de la liste casse le rafraichissement AVANT
        //      qu'il n'atteigne l'API : deconnexion toutes les 15 minutes, sans
        //      la moindre erreur serveur ;
        //    - mettre "*" dans GARAH_CORS_ORIGINS annulerait la protection
        //      entiere. C'est pourquoi le joker est interdit plus bas.
        configuration.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Accept-Language",
                        com.garah.api.config.FiltreOrigineCsrf.ENTETE));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return new CorsFilter(source);
    }
}
