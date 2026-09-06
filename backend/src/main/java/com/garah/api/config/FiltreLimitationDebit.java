package com.garah.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garah.api.commun.erreur.ReponseErreur;
import com.garah.api.commun.web.AdresseClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limite le débit sur les routes non authentifiées et coûteuses (D-24).
 *
 * <h2>Pourquoi seulement celles-là</h2>
 *
 * <p>Le reste de l'API exige un jeton : abuser d'une route protégée suppose
 * déjà un compte, donc une identité, donc la possibilité de le bloquer. Les
 * routes ci-dessous sont les seules qu'un inconnu peut marteler.</p>
 *
 * <pre>
 * /api/auth/inscription        crée un compte, 250 ms de BCrypt par appel
 * /api/auth/connexion          permet de chercher un mot de passe
 * /api/auth/verification/renvoi envoie un e-mail, à notre nom et sur notre quota
 * </pre>
 *
 * <h2>⚠️ La limite porte sur l'adresse IP, avec ce que ça implique</h2>
 *
 * <p>Derrière un partage de connexion — un cybercafé de Douala, un opérateur
 * qui masque ses abonnés derrière quelques adresses — <b>plusieurs personnes
 * partagent un compteur</b>. Les plafonds sont donc choisis larges : gêner un
 * client légitime coûte une vente, alors que ralentir un attaquant de 10 à 5
 * tentatives par minute suffit déjà à rendre son attaque inutile.</p>
 *
 * <p>Ce n'est pas une protection contre un attaquant déterminé disposant de
 * milliers d'adresses. C'est une protection contre le script trivial — qui est
 * l'écrasante majorité de ce qui frappe une API publique.</p>
 */
@Component
public class FiltreLimitationDebit extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltreLimitationDebit.class);

    private final Map<String, LimiteurDebit> limites = new LinkedHashMap<>();
    private final ObjectMapper json;
    private final boolean actif;

    public FiltreLimitationDebit(ObjectMapper json,
                                 @Value("${garah.limitation-debit.active:true}") boolean actif) {
        this.json = json;
        this.actif = actif;

        // 5 inscriptions par heure et par adresse. Une personne réelle en fait
        // UNE. Cinq laisse la place aux erreurs de saisie et au partage de
        // connexion, tout en rendant la création en masse inopérante.
        limites.put("POST /api/auth/inscription",
                new LimiteurDebit(5, Duration.ofHours(1)));

        // 10 tentatives par 5 minutes. Assez pour qui cherche son mot de passe,
        // beaucoup trop peu pour une attaque par dictionnaire.
        //
        // ⚠️ Volontairement PAS par adresse e-mail : compter par compte
        // permettrait de verrouiller n'importe qui en échouant à sa place.
        // La protection deviendrait l'attaque.
        limites.put("POST /api/auth/connexion",
                new LimiteurDebit(10, Duration.ofMinutes(5)));

        // 3 renvois par heure. Chaque appel envoie un e-mail : au-delà, c'est
        // notre réputation d'expéditeur qui s'abîme, et le quota gratuit qui
        // s'épuise.
        limites.put("POST /api/auth/verification/renvoi",
                new LimiteurDebit(3, Duration.ofHours(1)));

        if (!actif) {
            log.warn("La limitation de debit est DESACTIVEE. Acceptable en test, "
                    + "jamais en production.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                    FilterChain suite) throws ServletException, IOException {

        LimiteurDebit limite = actif
                ? limites.get(requete.getMethod() + " " + requete.getRequestURI())
                : null;

        if (limite == null) {
            suite.doFilter(requete, reponse);
            return;
        }

        // AdresseClient lit X-Forwarded-For, que le Worker Cloudflare renseigne
        // avec la vraie IP du visiteur (D-22). Sans cela, tout le trafic
        // partagerait un seul compteur et le premier inscrit de la journée
        // bloquerait tous les suivants.
        long attente = limite.tenter(AdresseClient.de(requete));

        if (attente > 0) {
            log.warn("Limite de debit atteinte sur {} {} : {} s d'attente.",
                    requete.getMethod(), requete.getRequestURI(), attente);

            // Retry-After : un client correct l'utilise pour patienter au lieu
            // de réessayer en boucle. Sans lui, un frontend mal écrit
            // transforme le refus en martèlement.
            reponse.setHeader("Retry-After", String.valueOf(attente));
            reponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            reponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            reponse.setCharacterEncoding("UTF-8");

            json.writeValue(reponse.getWriter(), ReponseErreur.de(
                    "TROP_DE_REQUETES",
                    "Trop de tentatives. Réessayez dans " + attente + " secondes.",
                    requete.getRequestURI()));
            return;
        }

        suite.doFilter(requete, reponse);
    }
}
