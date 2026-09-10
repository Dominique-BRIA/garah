package com.garah.api.config;

import com.garah.api.iam.domaine.DroitsParType;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
     * Traduit le jeton en autorisations Spring.
     *
     * <p>Aucun préfixe : nos autorisations <b>sont</b> les codes du référentiel
     * {@code cas_utilisation}, tels quels. Ainsi
     * {@code @PreAuthorize("hasAuthority('PRODUIT_PUBLIER')")} utilise
     * exactement le code de la base — sans traduction, donc sans erreur
     * possible.</p>
     *
     * <h2>🎯 Deux sources, selon que les droits se déduisent ou non</h2>
     *
     * <pre>
     * SUPER_ADMIN, ADMIN   déduits du claim `type` + le catalogue
     * RESPONSABLE          lus dans le claim `permissions`
     * </pre>
     *
     * <p>Les droits d'un super-administrateur sont « toutes les fonctionnalités
     * actives » : une fonction de son type, pas une information sur lui. Les
     * énumérer dans le jeton faisait voyager 197 codes et près de 7 Ko
     * d'en-tête sur chaque appel — jusqu'à dépasser la limite du serveur
     * (D-34). Le claim {@code type} suffit à les retrouver.</p>
     *
     * <p>Ceux d'un responsable, eux, <b>sont</b> une donnée le concernant — ses
     * profils, ses exceptions. Rien ne permet de les recalculer sans lire la
     * base, et c'est précisément ce que D-16 voulait éviter à chaque requête.
     * Ils continuent donc de voyager.</p>
     *
     * <h2>⚠️ Ce qu'on accepte en échange</h2>
     *
     * <p>L'autorisation d'un administrateur dépend désormais du catalogue, donc
     * d'une lecture en base — <b>faite une seule fois</b> par instance, le
     * référentiel n'étant écrit que par les migrations ({@code DroitsParType}).
     * Si cette lecture échoue, l'exception remonte et l'appel est refusé. C'est
     * voulu : rendre un ensemble vide aurait produit une pluie de 403 sur un
     * compte qui a tous les droits — un symptôme qu'on chercherait longtemps
     * du côté des permissions.</p>
     */
    @Bean
    public JwtAuthenticationConverter convertisseurJeton(DroitsParType droitsDeduits) {
        JwtGrantedAuthoritiesConverter duClaim = new JwtGrantedAuthoritiesConverter();
        duClaim.setAuthoritiesClaimName("permissions");
        duClaim.setAuthorityPrefix("");

        JwtAuthenticationConverter convertisseur = new JwtAuthenticationConverter();
        convertisseur.setJwtGrantedAuthoritiesConverter(jeton -> {
            TypeUtilisateur type = typeDe(jeton);

            if (type != null && droitsDeduits.seDeduisent(type)) {
                return droitsDeduits.pour(type).stream()
                        .map(code -> (GrantedAuthority) new SimpleGrantedAuthority(code))
                        .toList();
            }
            return duClaim.convert(jeton);
        });
        return convertisseur;
    }

    /**
     * Le type porté par le jeton, ou {@code null} s'il est illisible.
     *
     * <p>⚠️ Un type inconnu ne doit pas lever : un jeton d'une version
     * antérieure, ou forgé, ferait alors tomber la requête sur une erreur
     * technique au lieu d'un refus propre. On retombe sur le claim
     * {@code permissions} — absent dans ce cas — donc sur aucune autorisation,
     * c'est-à-dire un refus.</p>
     */
    private static TypeUtilisateur typeDe(Jwt jeton) {
        try {
            String declare = jeton.getClaimAsString("type");
            return declare == null ? null : TypeUtilisateur.valueOf(declare);
        } catch (IllegalArgumentException inconnu) {
            return null;
        }
    }

    @Bean
    public SecurityFilterChain chaineDeFiltres(HttpSecurity http,
                                               JwtAuthenticationConverter convertisseur) throws Exception {
        http
                // Un jeton Bearer est immunisé au CSRF parce que le navigateur
                // ne l'ajoute jamais tout seul : il faut du JavaScript, donc
                // l'origine attaquante devrait lire le jeton, ce que la
                // politique d'origine lui interdit.
                //
                // Un COOKIE, lui, part tout seul. Et comme il doit être en
                // SameSite=None (Vercel et Render sont deux sites différents,
                // D-14), il part AUSSI depuis un site tiers. C'est ce que
                // FiltreOrigineCsrf ferme, sur les deux routes concernées.
                //
                // ⚠️ Le mécanisme CSRF de Spring est désactivé — et remplacé,
                //    pas supprimé. Voir FiltreOrigineCsrf plus bas.
                //
                // Pourquoi ne PAS utiliser le « double-submit cookie » de
                // Spring, qui semblait pourtant taillé pour ça :
                //
                //   API        garah-api.onrender.com   ← pose le cookie XSRF-TOKEN
                //   frontend   garah-client.vercel.app  ← NE PEUT PAS LE LIRE
                //
                // Un cookie n'est lisible en JavaScript que depuis SON domaine.
                // Angular, servi depuis Vercel, n'a aucun accès au cookie posé
                // par Render : il n'aurait jamais rien à renvoyer, et chaque
                // rafraîchissement répondrait 403. Le double-submit suppose que
                // l'API et la page partagent une origine — ce n'est pas notre
                // cas (D-14), et ce ne le sera qu'avec un domaine commun.
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
                        // ⚠️ LA POIGNÉE DE MAIN WEBSOCKET EST ANONYME, ET
                        //    ELLE DOIT L'ÊTRE.
                        //
                        // L'API WebSocket du navigateur ne permet pas
                        // d'ajouter un en-tête Authorization à l'ouverture.
                        // C'est une limite du standard : les seules façons de
                        // porter le jeton dans la poignée de main seraient
                        // l'URL — où il finirait dans les journaux du serveur
                        // et du mandataire — ou un cookie, qui rouvrirait le
                        // CSRF que FiltreOrigineCsrf ferme ailleurs.
                        //
                        // Le jeton est donc contrôlé sur la trame STOMP
                        // CONNECT, dans ConfigurationWebSocket. Sans jeton
                        // valide, la session reste anonyme et AUCUNE file
                        // personnelle ne lui est jamais adressée : elle
                        // n'apprend rien.
                        //
                        // ⚠️ SANS CETTE LIGNE, /ws répond 401 et rien ne
                        //    fonctionne — mais rien ne le dit non plus. Le
                        //    navigateur signale seulement une connexion
                        //    fermée, le client retente indéfiniment, et
                        //    l'écran a simplement l'air de ne pas se mettre à
                        //    jour. C'est exactement ce qui est arrivé.
                        .requestMatchers("/ws").permitAll()

                        .requestMatchers("/api/sante").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/configuration").permitAll()

                        // L'arbre des catégories : la vitrine en a besoin pour
                        // son menu, et rien n'y est confidentiel — c'est du
                        // contenu destiné à être vu.
                        //
                        // GET seulement : le POST qui crée une catégorie reste
                        // protégé par CATEGORIE_PRODUIT_GERER. Ouvrir la route
                        // sans préciser la méthode aurait ouvert la création.
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/connexion").permitAll()

                        // La porte d'entrée : sans elle, aucun client ne peut
                        // exister, et D-07 (compte obligatoire pour commander)
                        // rend alors tout le tunnel de vente inatteignable.
                        //
                        // ⚠️ Cette route crée un compte sans vérification
                        // d'identité et coûte 250 ms de BCrypt par appel. Elle
                        // doit être placée derrière une limitation de débit
                        // avant l'ouverture au public.
                        .requestMatchers(HttpMethod.POST, "/api/auth/inscription").permitAll()

                        // Rafraîchissement et déconnexion s'authentifient par
                        // COOKIE, pas par jeton Bearer. Elles doivent donc
                        // passer la chaîne de filtres sans exiger de JWT —
                        // sinon /api/auth/rafraichir répondrait 401 précisément
                        // quand le jeton d'accès a expiré, c'est-à-dire dans le
                        // seul cas où on l'appelle.
                        //
                        // « permitAll » ne veut pas dire « sans contrôle » :
                        // le cookie est vérifié dans le service, et CSRF
                        // s'applique ici (voir ROUTES_A_COOKIE ci-dessus).
                        .requestMatchers(HttpMethod.POST, "/api/auth/rafraichir").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/deconnexion").permitAll()

                        // Le lien de confirmation reçu par e-mail. PUBLIQUE, et
                        // elle doit l'être : celui qui clique vient d'ouvrir sa
                        // boîte mail, souvent sur un autre appareil, et n'a aucun
                        // jeton. Ce qui protège cette route n'est pas
                        // l'authentification mais le JETON lui-même — 256 bits
                        // tirés au sort, usage unique, 48 heures (D-23).
                        .requestMatchers(HttpMethod.GET, "/api/auth/verification").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/verification").permitAll()

                        // 🎯 Le webhook de l'opérateur de paiement.
                        //
                        // Publique — elle ne peut pas être autrement : Campay
                        // n'a pas de compte chez nous et ne portera jamais de
                        // jeton. C'est le cas général des webhooks.
                        //
                        // Ce qui la protège n'est donc PAS l'authentification,
                        // mais le fait qu'elle ne croit rien de ce qu'on lui
                        // envoie : elle n'en retient qu'une référence, puis
                        // redemande l'état réel à Campay sur une connexion que
                        // NOUS ouvrons (voir ServicePaiementMobile).
                        //
                        // Poster ici une notification forgée ne permet donc ni
                        // de déclarer une commande payée, ni d'en changer le
                        // montant.
                        .requestMatchers(HttpMethod.POST, "/api/paiements/notifications/campay")
                            .permitAll()

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

                        // ⚠️ L'ORDRE COMPTE. Cette ligne doit précéder
                        // /api/produits/{slug}, sinon « tendance » serait pris
                        // pour un slug de produit — la route répondrait 404, et
                        // le bloc « produits tendance » de la page d'accueil
                        // resterait vide sans qu'aucune erreur n'apparaisse.
                        .requestMatchers(HttpMethod.GET, "/api/produits/tendance").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/produits/{slug}").permitAll()

                        // ⚠️ UNE LIGNE À PART, et c'est la règle qui l'exige.
                        //
                        // `/api/produits/{slug}` ne couvre PAS
                        // `/api/produits/{slug}/vitrine` : un motif ne vaut que
                        // pour le nombre de segments qu'il décrit. Sans cette
                        // ligne, la fiche vitrine exigerait une connexion — et
                        // la boutique deviendrait invisible aux visiteurs.
                        //
                        // C'est le bon défaut : « on liste ce qui est ouvert,
                        // jamais ce qui est fermé ». Un oubli laisse une route
                        // FERMÉE, jamais ouverte par mégarde.
                        .requestMatchers(HttpMethod.GET, "/api/produits/{slug}/vitrine").permitAll()

                        // Le choix du point de récupération se fait AVANT
                        // l'inscription (D-05) : la vitrine doit pouvoir
                        // annoncer « disponible à Douala, Bangui » à un
                        // visiteur anonyme.
                        .requestMatchers(HttpMethod.GET, "/api/lieux/points-recuperation")
                            .permitAll()

                        // Le suivi d'un colis par son numéro. Volontairement
                        // pauvre : le parcours et rien d'autre — un numéro de
                        // suivi circule par SMS et ne prouve aucune identité.
                        .requestMatchers(HttpMethod.GET, "/api/expeditions/suivi/{numeroSuivi}")
                            .permitAll()

                        // La mesure d'audience de la vitrine. Exiger un jeton
                        // ici ne compterait plus que les clients déjà acquis,
                        // c'est-à-dire l'inverse de ce qu'on veut mesurer.
                        .requestMatchers(HttpMethod.POST, "/api/produits/{produitId}/vues")
                            .permitAll()

                        // Tout le reste exige un jeton valide. On liste ce qui
                        // est ouvert, jamais ce qui est fermé : un oubli laisse
                        // alors une route fermée, pas ouverte.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(convertisseur)));

        return http.build();
    }
}
