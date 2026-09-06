package com.garah.api.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Sécurité de l'API : sans session, par jeton signé.
 */
@Configuration
@EnableMethodSecurity   // active @PreAuthorize sur les services et contrôleurs
public class ConfigurationSecurite {

    @Value("${GARAH_JWT_SECRET}")
    private String secret;

    /**
     * BCrypt, avec un coût de 12.
     *
     * <p>BCrypt est <b>volontairement lent</b> : c'est sa raison d'être. Chaque
     * incrément de coût double le temps de calcul, ce qui divise par deux la
     * vitesse d'une attaque par force brute. Le coût 12 tient environ 250 ms,
     * imperceptible pour un humain qui se connecte, très coûteux pour qui
     * essaie des millions de mots de passe.</p>
     *
     * <p>⚠️ Ne jamais utiliser MD5, SHA-1 ou SHA-256 pour un mot de passe :
     * ils sont conçus pour être <b>rapides</b>, donc parfaits pour l'attaquant.</p>
     */
    @Bean
    public PasswordEncoder encodeurMotDePasse() {
        return new BCryptPasswordEncoder(12);
    }

    private SecretKeySpec cle() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder encodeurJeton() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(cle()));
    }

    @Bean
    public JwtDecoder decodeurJeton() {
        return NimbusJwtDecoder.withSecretKey(cle())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Traduit le claim {@code permissions} du jeton en autorisations Spring.
     *
     * <p>Par défaut, Spring cherche un claim {@code scope} et préfixe les
     * valeurs par {@code SCOPE_}. On désactive le préfixe : nos autorisations
     * sont les codes du référentiel {@code cas_utilisation}, tels quels.
     * Ainsi {@code @PreAuthorize("hasAuthority('PRODUIT_PUBLIER')")} utilise
     * exactement le code de la base — sans traduction, donc sans erreur
     * possible.</p>
     */
    @Bean
    public JwtAuthenticationConverter convertisseurJeton() {
        JwtGrantedAuthoritiesConverter autorites = new JwtGrantedAuthoritiesConverter();
        autorites.setAuthoritiesClaimName("permissions");
        autorites.setAuthorityPrefix("");

        JwtAuthenticationConverter convertisseur = new JwtAuthenticationConverter();
        convertisseur.setJwtGrantedAuthoritiesConverter(autorites);
        return convertisseur;
    }

    @Bean
    public SecurityFilterChain chaineDeFiltres(HttpSecurity http,
                                               JwtAuthenticationConverter convertisseur) throws Exception {
        http
                // CSRF ne sert à rien ici : l'API est sans session et
                // l'authentification passe par un en-tête, pas par un cookie
                // que le navigateur enverrait tout seul.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requetes -> requetes
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ⚠️ Chaque route publique est listée UNE PAR UNE.
                        //
                        // La première version écrivait "/api/auth/**", ce qui
                        // paraissait raisonnable — et ouvrait du même coup
                        // /api/auth/moi, qui doit être protégée. La route
                        // répondait alors 500 au lieu de 401, parce qu'elle
                        // atteignait le contrôleur sans jeton.
                        //
                        // Un joker dans une règle de sécurité ouvre TOUJOURS
                        // plus que ce qu'on avait en tête.
                        .requestMatchers("/api/sante").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/configuration").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/connexion").permitAll()

                        // La vitrine est ouverte : elle doit lire le catalogue
                        // sans jeton, sinon aucun visiteur ne voit un produit.
                        //
                        // Ces deux lignes manquaient jusqu'au chapitre 21, et
                        // rien ne le signalait : aucun test ne passait par
                        // HTTP, et tous les tests métier appellent les services
                        // directement — là où la sécurité web n'intervient pas.
                        //
                        // La MÉTHODE est précisée. Sans HttpMethod.GET, la même
                        // règle ouvrirait POST /api/produits, c'est-à-dire la
                        // création de produit.
                        .requestMatchers(HttpMethod.GET, "/api/produits").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/produits/{slug}").permitAll()

                        // Tout le reste exige un jeton valide. On liste ce qui
                        // est ouvert, jamais ce qui est fermé : un oubli laisse
                        // alors une route fermée, pas ouverte.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(convertisseur)));

        return http.build();
    }
}
