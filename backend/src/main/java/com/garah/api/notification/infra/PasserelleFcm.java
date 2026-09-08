package com.garah.api.notification.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * L'envoi de notifications, par l'API HTTP v1 de Firebase.
 *
 * <h2>🎯 SANS le SDK {@code firebase-admin}, et c'est un choix</h2>
 *
 * <p>Ce SDK apporte gRPC, protobuf, Guava et leurs dépendances : des dizaines
 * de mégaoctets à télécharger à chaque construction, pour <b>un seul appel
 * HTTP</b>. Sur ce projet, où la connexion se compte, le rapport n'y est pas.</p>
 *
 * <p>Ce qu'il fait vraiment tient en deux gestes : signer un jeton avec la clé
 * du compte de service pour obtenir un jeton d'accès Google, puis poster du
 * JSON. Nimbus JOSE — déjà présent pour les jetons de session — sait signer en
 * RS256, et {@code RestClient} sait poster.</p>
 *
 * <h2>⚠️ La clé du compte de service est un VRAI secret</h2>
 *
 * <p>Contrairement à {@code google-services.json}, qui est embarqué dans
 * chaque APK et se lit au désassemblage. Celle-ci permet d'<b>envoyer</b> au
 * nom du projet : elle vit dans les réglages d'Azure, jamais dans le dépôt.</p>
 *
 * <h2>⚠️ Absente, la passerelle se TAIT — elle ne casse rien</h2>
 *
 * <p>En local et en test, la variable n'est pas posée. Une notification qui ne
 * part pas est regrettable ; un serveur qui refuse de démarrer parce qu'une
 * notification ne peut pas partir est absurde.</p>
 */
@Component
public class PasserelleFcm {

    private static final Logger JOURNAL = LoggerFactory.getLogger(PasserelleFcm.class);

    private static final String PORTEE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String JETON_GOOGLE = "https://oauth2.googleapis.com/token";

    /**
     * Le jeton d'accès vaut une heure ; on le renouvelle cinq minutes avant.
     *
     * <p>⚠️ Le renouveler à chaque envoi ferait un aller-retour vers Google
     * <b>avant chaque notification</b> — soit deux appels réseau au lieu d'un,
     * et une latence doublée sur un geste déjà asynchrone.</p>
     */
    private static final Duration MARGE = Duration.ofMinutes(5);

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    private final String projetId;
    private final String emailCompte;
    private final RSAPrivateKey cle;

    private String jetonAcces;
    private Instant expiration = Instant.EPOCH;

    public PasserelleFcm(@Value("${GARAH_FIREBASE_CREDENTIALS:}") String identifiants,
                         RestClient.Builder constructeur) {
        this.http = constructeur.build();

        String projet = null;
        String email = null;
        RSAPrivateKey cleLue = null;

        // ⚠️ On teste le CONTENU, jamais la présence : une variable posée à
        //    vide sur Azure écrase le défaut de @Value, et « définie » ne veut
        //    pas dire « renseignée ».
        if (identifiants != null && !identifiants.isBlank()) {
            try {
                JsonNode compte = json.readTree(identifiants);
                projet = compte.path("project_id").asText(null);
                email = compte.path("client_email").asText(null);
                cleLue = lireLaCle(compte.path("private_key").asText(""));
            } catch (Exception e) {
                // On journalise et on continue muet : une clé mal collée ne
                // doit pas empêcher le serveur de démarrer.
                JOURNAL.error("GARAH_FIREBASE_CREDENTIALS est illisible. "
                        + "Les notifications ne partiront pas.", e);
                projet = null;
                email = null;
                cleLue = null;
            }
        }

        this.projetId = projet;
        this.emailCompte = email;
        this.cle = cleLue;

        if (!active()) {
            JOURNAL.warn("Les notifications poussees sont DESACTIVEES "
                    + "(GARAH_FIREBASE_CREDENTIALS absent). "
                    + "Acceptable en local et en test, jamais en production.");
        }
    }

    public boolean active() {
        return projetId != null && emailCompte != null && cle != null;
    }

    /**
     * Envoie une notification à un appareil.
     *
     * <p>Rend {@code false} si le jeton est <b>mort</b> — application
     * désinstallée, jeton renouvelé. L'appelant s'en sert pour faire le ménage :
     * un jeton mort qu'on garde est un envoi facturé qui échouera à chaque
     * fois.</p>
     *
     * <p>⚠️ {@code data} et non {@code notification} seule. Avec un bloc
     * {@code notification}, Android affiche la bannière lui-même quand
     * l'application est en arrière-plan, mais ne transmet <b>pas</b> les
     * données à l'application : toucher la bannière ouvrirait l'accueil au
     * lieu de la commande concernée.</p>
     */
    public boolean envoyer(String jetonAppareil, String titre, String corps,
                           Map<String, String> donnees) {
        if (!active()) {
            return true;
        }

        try {
            Map<String, Object> message = Map.of(
                    "message", Map.of(
                            "token", jetonAppareil,
                            "notification", Map.of("title", titre, "body", corps),
                            "data", donnees,
                            // Une priorité haute : une marchandise arrivée ne
                            // doit pas attendre que le téléphone se réveille de
                            // lui-même. Réservé aux trois moments qui comptent.
                            "android", Map.of("priority", "high")));

            http.post()
                    .uri("https://fcm.googleapis.com/v1/projects/" + projetId + "/messages:send")
                    .header("Authorization", "Bearer " + jetonAcces())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
            return true;

        } catch (RestClientException echec) {
            String texte = echec.getMessage() == null ? "" : echec.getMessage();
            // 404 UNREGISTERED, 400 INVALID_ARGUMENT sur le jeton : l'appareil
            // n'existe plus. C'est le signal de radiation.
            boolean jetonMort = texte.contains("404") || texte.contains("UNREGISTERED");
            if (!jetonMort) {
                JOURNAL.warn("Notification non delivree : {}", texte);
            }
            return !jetonMort;
        } catch (Exception e) {
            JOURNAL.warn("Notification non delivree.", e);
            return true;
        }
    }

    /**
     * Le jeton d'accès Google, obtenu par un JWT auto-signé.
     *
     * <p>C'est le flux « compte de service » : on signe soi-même une assertion
     * avec la clé privée, et Google la troque contre un jeton d'accès. Aucune
     * bibliothèque n'est nécessaire pour cela — seulement une signature RS256.</p>
     */
    private synchronized String jetonAcces() throws Exception {
        if (jetonAcces != null && Instant.now().isBefore(expiration.minus(MARGE))) {
            return jetonAcces;
        }

        Instant maintenant = Instant.now();
        SignedJWT assertion = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder()
                        .issuer(emailCompte)
                        .audience(JETON_GOOGLE)
                        .claim("scope", PORTEE)
                        .issueTime(Date.from(maintenant))
                        .expirationTime(Date.from(maintenant.plus(Duration.ofHours(1))))
                        .build());
        assertion.sign(new RSASSASigner(cle));

        JsonNode reponse = json.readTree(http.post()
                .uri(JETON_GOOGLE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer"
                        + "&assertion=" + assertion.serialize())
                .retrieve()
                .body(String.class));

        jetonAcces = reponse.path("access_token").asText(null);
        expiration = maintenant.plusSeconds(reponse.path("expires_in").asLong(3600));
        return jetonAcces;
    }

    /**
     * Lit la clé PEM du fichier de compte de service.
     *
     * <p>⚠️ Les sauts de ligne y sont échappés en {@code \\n} : le JSON les
     * porte ainsi, et Jackson les rend déjà décodés. Mais une clé recopiée à la
     * main dans une variable d'environnement les garde parfois littéraux —
     * d'où le remplacement, qui ne coûte rien et évite une erreur illisible.</p>
     */
    private static RSAPrivateKey lireLaCle(String pem) throws Exception {
        String base64 = pem
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] octets = Base64.getDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(octets));
    }
}
