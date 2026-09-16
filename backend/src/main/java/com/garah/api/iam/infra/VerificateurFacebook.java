package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.FournisseurIdentite;
import com.garah.api.iam.domaine.IdentiteVerifiee;
import com.garah.api.iam.domaine.VerificateurIdentiteSociale.JetonSocialInvalide;
import com.garah.api.iam.domaine.VerificateurParFournisseur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * « Continuer avec Facebook ».
 *
 * <h2>Pourquoi ce n'est pas Google, et ne peut pas l'être</h2>
 *
 * <p>Facebook ne rend pas un jeton signé qu'on vérifierait hors ligne. Il rend
 * un <b>jeton d'accès opaque</b> — une chaîne qui ne dit rien par elle-même.
 * Il faut donc <b>interroger Meta</b>, en ligne, à chaque connexion :</p>
 *
 * <pre>
 * Google     1 verification de signature, hors ligne, ~1 ms
 * Facebook   2 appels reseau : debug_token, puis /me
 * </pre>
 *
 * <p>Conséquence : une panne de Meta rend « Continuer avec Facebook »
 * indisponible, là où Google continuerait de fonctionner tant que le JWKS est
 * en cache. C'est une dépendance de disponibilité qu'on n'avait pas.</p>
 *
 * <h2>🎯 Le contrôle que tout le monde oublie</h2>
 *
 * <p>Beaucoup d'intégrations appellent directement {@code /me} avec le jeton
 * reçu et ouvrent la session sur le résultat. <b>C'est une faille.</b> Un jeton
 * d'accès Facebook est valide pour <i>l'application qui l'a demandé</i>, pas
 * pour la nôtre : n'importe quel développeur ayant sa propre application
 * Facebook peut en obtenir un, l'envoyer à notre API, et se connecter en tant
 * que la personne qui l'a autorisé — sans jamais toucher à GARAH.</p>
 *
 * <p>{@code /debug_token} est ce qui ferme cette porte : il dit pour
 * <b>quelle application</b> le jeton a été émis, et on refuse s'il ne s'agit
 * pas de la nôtre. C'est l'exact équivalent du contrôle du {@code aud} chez
 * Google.</p>
 *
 * <h2>⚠️ L'adresse e-mail n'est pas garantie</h2>
 *
 * <p>La permission {@code email} exige App Review et vérification
 * d'entreprise. Et même accordée, elle ne donne rien pour un compte créé par
 * <b>numéro de téléphone</b> — Facebook en accepte, et ces comptes n'ont
 * aucune adresse.</p>
 *
 * <p>D'où {@code fournitUnEmailVerifie = false} sur l'énumération : Facebook ne
 * peut <b>jamais</b> rattacher un compte GARAH existant tout seul. Le service
 * renverra {@code ADRESSE_INDISPONIBLE} quand rien ne revient, et c'est à
 * l'écran de demander l'adresse.</p>
 */
@Component
public class VerificateurFacebook implements VerificateurParFournisseur {

    private static final Logger log = LoggerFactory.getLogger(VerificateurFacebook.class);

    private final String identifiantApplication;
    private final String secret;
    private final RestClient http;

    public VerificateurFacebook(
            @Value("${GARAH_FACEBOOK_APP_ID:}") String identifiantApplication,
            @Value("${GARAH_FACEBOOK_APP_SECRET:}") String secret,
            @Value("${GARAH_FACEBOOK_API:https://graph.facebook.com/v21.0}") String base) {

        this.identifiantApplication = identifiantApplication == null ? "" : identifiantApplication.strip();
        this.secret = secret == null ? "" : secret.strip();
        this.http = RestClient.builder().baseUrl(base).build();

        if (!estActif()) {
            log.warn("Facebook non configure (GARAH_FACEBOOK_APP_ID / _APP_SECRET) : "
                    + "« Continuer avec Facebook » ne sera pas propose.");
        }
    }

    @Override
    public FournisseurIdentite fournisseur() {
        return FournisseurIdentite.FACEBOOK;
    }

    @Override
    public boolean estActif() {
        return !identifiantApplication.isEmpty() && !secret.isEmpty();
    }

    @Override
    public IdentiteVerifiee verifier(String jetonAcces) {
        verifierQueLeJetonEstPourNous(jetonAcces);
        return lireLeProfil(jetonAcces);
    }

    /**
     * ⚠️ <b>Le contrôle central. Ne jamais le retirer « pour simplifier ».</b>
     *
     * <p>Sans lui, un jeton émis pour l'application de quelqu'un d'autre ouvre
     * une session chez nous.</p>
     *
     * <p>Le jeton d'application est {@code <app_id>|<app_secret>} — une forme
     * que Meta accepte pour éviter un appel préalable. Le <b>secret</b> ne
     * quitte donc jamais le serveur, et c'est bien pour cela que cette
     * vérification ne peut pas être faite côté client.</p>
     */
    private void verifierQueLeJetonEstPourNous(String jetonAcces) {
        Map<?, ?> reponse;
        try {
            reponse = http.get()
                    .uri(builder -> builder.path("/debug_token")
                            .queryParam("input_token", jetonAcces)
                            .queryParam("access_token", identifiantApplication + "|" + secret)
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            log.debug("debug_token Facebook en echec : {}", e.getMessage());
            throw new JetonSocialInvalide();
        }

        Object brut = reponse == null ? null : reponse.get("data");
        if (!(brut instanceof Map<?, ?> donnees)) {
            throw new JetonSocialInvalide();
        }

        if (!Boolean.TRUE.equals(donnees.get("is_valid"))) {
            throw new JetonSocialInvalide();
        }

        if (!identifiantApplication.equals(String.valueOf(donnees.get("app_id")))) {
            log.warn("Jeton Facebook valide mais emis pour l application {} — refuse",
                    donnees.get("app_id"));
            throw new JetonSocialInvalide();
        }
    }

    private IdentiteVerifiee lireLeProfil(String jetonAcces) {
        Map<?, ?> profil;
        try {
            profil = http.get()
                    .uri(builder -> builder.path("/me")
                            .queryParam("fields", "id,name,email")
                            .queryParam("access_token", jetonAcces)
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            log.debug("Lecture du profil Facebook en echec : {}", e.getMessage());
            throw new JetonSocialInvalide();
        }

        String sujet = profil == null ? null : String.valueOf(profil.get("id"));
        if (sujet == null || sujet.isBlank() || "null".equals(sujet)) {
            throw new JetonSocialInvalide();
        }

        Object email = profil.get("email");

        return new IdentiteVerifiee(
                FournisseurIdentite.FACEBOOK,
                sujet,
                email == null ? null : String.valueOf(email),
                // ⚠️ TOUJOURS false, même quand Facebook renvoie une adresse.
                //
                //    Meta ne dit pas s'il l'a vérifiée, et une adresse non
                //    attestée ne doit jamais permettre de rattacher un compte
                //    GARAH existant : il suffirait de déclarer l'adresse de sa
                //    victime chez Facebook pour prendre son compte ici.
                false,
                profil.get("name") == null ? null : String.valueOf(profil.get("name")));
    }
}
