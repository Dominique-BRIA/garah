package com.garah.api.commerce.infra;

import com.garah.api.commun.erreur.ErreurMetier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * La passerelle vers Campay — l'agrégateur MTN MoMo + Orange Money (D-06).
 *
 * <p>Une seule classe parle HTTP à l'opérateur. Tout le reste du code manipule
 * des {@link Collecte} et des {@link EtatTransaction} : le jour où Campay est
 * remplacé, c'est ce fichier qu'on réécrit, et lui seul.</p>
 *
 * <h2>Ce que fait Campay</h2>
 * <pre>
 * POST {base}/token/              {username, password}  → {token, expires_in}
 * POST {base}/collect/            {amount, from, ...}   → {reference, ussd_code, operator}
 * GET  {base}/transaction/{ref}/                        → {status, amount, ...}
 * </pre>
 *
 * <p>Le jeton est mis en cache : le redemander à chaque appel doublerait la
 * latence d'un paiement, et l'instance Render gratuite est déjà lente au
 * réveil (D-14).</p>
 *
 * <p>⚠️ <b>Aucun secret n'est écrit dans les journaux.</b> Ni le mot de passe,
 * ni le jeton. Le message d'une exception HTTP contient souvent le corps de la
 * requête : on ne journalise donc que la CLASSE de l'exception, jamais son
 * message. Un journal se copie, se transmet au support, et finit dans un
 * fichier que personne ne surveille.</p>
 */
@Component
public class ClientCampay {

    private static final Logger log = LoggerFactory.getLogger(ClientCampay.class);

    private static final ParameterizedTypeReference<Map<String, Object>> CARTE =
            new ParameterizedTypeReference<>() {
            };

    /**
     * Marge de sécurité sur l'expiration du jeton.
     *
     * <p>Sans elle, un jeton valable jusqu'à 11 h 00 serait encore utilisé à
     * 10 h 59 min 59 s — et l'appel partirait avec un jeton périmé à l'arrivée.
     * On renouvelle une minute avant.</p>
     */
    private static final Duration MARGE_EXPIRATION = Duration.ofMinutes(1);

    private final RestClient http;
    private final String utilisateur;
    private final String motDePasse;
    private final boolean configure;

    /** Jeton courant et péremption. {@code volatile} : plusieurs requêtes en parallèle. */
    private volatile String jeton;
    private volatile Instant jetonExpireLe = Instant.EPOCH;

    public ClientCampay(@Value("${GARAH_CAMPAY_BASE_URL:}") String baseUrl,
                        @Value("${GARAH_CAMPAY_APP_USERNAME:}") String utilisateur,
                        @Value("${GARAH_CAMPAY_APP_PASSWORD:}") String motDePasse,
                        RestClient.Builder constructeur) {

        this.utilisateur = utilisateur == null ? "" : utilisateur.strip();
        this.motDePasse = motDePasse == null ? "" : motDePasse.strip();

        String base = baseUrl == null ? "" : baseUrl.strip().replaceFirst("/+$", "");

        // ⚠️ Une variable VIDE n'est pas une variable ABSENTE — la leçon de
        // StockageObjet, qui avait cassé toutes les images du site. Un .env
        // partiellement rempli produit des chaînes vides, pas les valeurs par
        // défaut de @Value. On teste donc le CONTENU, jamais la présence.
        this.configure = !base.isBlank()
                && !this.utilisateur.isBlank()
                && !this.motDePasse.isBlank();

        this.http = constructeur
                .baseUrl(base.isBlank() ? "https://campay-non-configure.invalid" : base)
                .build();

        if (!configure) {
            log.warn("Campay n'est pas configure (GARAH_CAMPAY_BASE_URL / APP_USERNAME / "
                    + "APP_PASSWORD). Le paiement mobile money repondra 503 : AUCUNE commande "
                    + "ne pourra etre payee.");
        } else if (base.contains("demo.campay.net")) {
            log.warn("Campay est en BAC A SABLE ({}) : aucun encaissement reel.", base);
        }
    }

    /** Campay est-il utilisable ? Sert à répondre 503 plutôt qu'à planter. */
    public boolean estConfigure() {
        return configure;
    }

    /**
     * Le service de paiement est indisponible.
     *
     * <p>{@code 503}, pas {@code 500} : la commande du client est intacte, la
     * panne est chez nous ou chez l'opérateur, et réessayer plus tard a du
     * sens. Un {@code 500} dirait « bug », et le support chercherait au mauvais
     * endroit.</p>
     */
    /**
     * L'opérateur a <b>répondu</b>, et il a refusé.
     *
     * <h2>🎯 Ce que cette classe sépare</h2>
     *
     * <p>Tout échec d'appel devenait « le service de paiement est
     * momentanément injoignable ». C'était faux la moitié du temps : un
     * {@code 400} signifie que Campay a répondu parfaitement, pour dire
     * <b>non</b> — montant trop faible, numéro invalide, opérateur qui ne
     * correspond pas au préfixe.</p>
     *
     * <p>⚠️ Le coût du mélange : on cherche une panne réseau pendant que la
     * réponse était sur la table. Un paiement de 20 FCFA était refusé parce
     * que Campay exige un minimum, et l'écran annonçait une indisponibilité.</p>
     *
     * <p>{@code 422} et non {@code 503} : rien n'est en panne, et réessayer à
     * l'identique donnera le même refus.</p>
     */
    public static class OperateurRefuse extends ErreurMetier {
        public OperateurRefuse(String message) {
            super("OPERATEUR_REFUSE", message);
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    public static class OperateurIndisponible extends ErreurMetier {
        public OperateurIndisponible(String message) {
            super("OPERATEUR_INDISPONIBLE", message);
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /** Ce que Campay renvoie quand on lui demande d'encaisser. */
    public record Collecte(String reference, String codeUssd, String operateur) {
    }

    /**
     * L'état d'une transaction chez l'opérateur — <b>la source de vérité</b>.
     *
     * @param statut {@code SUCCESSFUL}, {@code FAILED} ou {@code PENDING}
     */
    public record EtatTransaction(String reference, String statut, BigDecimal montant,
                                  String operateur, String referenceOperateur,
                                  String codeErreur) {

        public boolean reussi() {
            return "SUCCESSFUL".equalsIgnoreCase(statut);
        }

        public boolean echoue() {
            return "FAILED".equalsIgnoreCase(statut);
        }

        /** Tout ce qui n'est ni un succès ni un échec est encore en cours. */
        public boolean enCours() {
            return !reussi() && !echoue();
        }
    }

    /**
     * Demande à l'opérateur de débiter le client.
     *
     * <p>Le client reçoit alors une invitation à saisir son code sur son
     * téléphone. <b>Rien n'est encaissé à ce stade</b> : la confirmation
     * arrivera par le webhook, ou par la réconciliation périodique.</p>
     *
     * @param referenceExterne notre propre identifiant de paiement — c'est lui
     *                         qui permet de rapprocher les deux systèmes le
     *                         jour d'un litige
     */
    /**
     * Le plus petit montant que l'opérateur accepte, en FCFA.
     *
     * <h2>🎯 Pourquoi il est vérifié ICI, avant l'appel</h2>
     *
     * <p>Campay refuse en dessous, par un {@code 400}. Sans ce contrôle, le
     * client déclenchait une demande de paiement, attendait, et recevait un
     * message d'erreur — pour une raison connue d'avance.</p>
     *
     * <p>C'est la règle du projet : <b>dire ce qui manque AVANT le clic</b>.
     * Le serveur refuserait de toute façon ; l'utilisateur ne doit pas
     * découvrir par une erreur ce que l'on savait déjà.</p>
     *
     * <p>⚠️ C'est une contrainte de l'opérateur, pas une règle GARAH. Elle
     * vit donc dans le client Campay, et non dans le domaine : le jour où l'on
     * ajoute un second opérateur, chacun apportera la sienne.</p>
     */
    public static final int MONTANT_MINIMUM = 100;

    public Collecte encaisser(BigDecimal montant, String telephone,
                              String description, String referenceExterne) {
        // ⚠️ LE MONTANT D'ABORD, avant même de regarder si l'on peut joindre
        //    quelqu'un : c'est une propriété de la DEMANDE, pas du serveur.
        //    Une demande malformée se refuse sans se demander qui l'aurait
        //    reçue.
        if (montant == null || montant.compareTo(BigDecimal.valueOf(MONTANT_MINIMUM)) < 0) {
            throw new OperateurRefuse(
                    "Le montant minimum accepté par l'opérateur est de "
                    + MONTANT_MINIMUM + " FCFA.");
        }

        exigerConfiguration();

        // ⚠️ Le XAF n'a PAS de centimes, et Campay refuse « 5000.00 ».

        // setScale(0, UNNECESSARY) lève si le montant a une partie décimale
        // non nulle — c'est voulu : mieux vaut une erreur ici qu'un débit
        // silencieusement arrondi.
        String montantEntier = montant.setScale(0, RoundingMode.UNNECESSARY).toPlainString();

        Map<String, Object> reponse = post("/collect/", Map.of(
                "amount", montantEntier,
                "currency", "XAF",
                "from", telephone,
                "description", description,
                "external_reference", referenceExterne));

        String reference = reponse == null ? null : texte(reponse, "reference");
        if (reference == null || reference.isBlank()) {
            // Sans référence, ce paiement ne pourra JAMAIS être rapproché.
            // Mieux vaut échouer maintenant que créer une ligne orpheline dont
            // personne ne saura jamais si elle a été débitée.
            log.error("Campay a accepte la collecte sans renvoyer de reference (paiement {})",
                    referenceExterne);
            throw new OperateurIndisponible(
                    "L'opérateur n'a pas renvoyé de référence de transaction.");
        }

        return new Collecte(reference, texte(reponse, "ussd_code"), texte(reponse, "operator"));
    }

    /**
     * L'état réel d'une transaction, demandé directement à Campay.
     *
     * <p>🎯 <b>C'est la méthode qui porte toute la sécurité du webhook.</b></p>
     *
     * <p>Une notification entrante n'est qu'un <i>signal</i> : « la transaction
     * X a bougé ». Ce qu'elle affirme du statut et du montant n'est jamais cru.
     * On revient demander ici, sur une connexion TLS que <b>nous</b> ouvrons,
     * avec <b>nos</b> identifiants.</p>
     *
     * <p>Conséquence : un inconnu qui appelle notre webhook n'obtient rien. Il
     * déclenche une question dont il ne contrôle pas la réponse.</p>
     *
     * @return vide si Campay ne connaît pas cette référence
     */
    public Optional<EtatTransaction> statut(String reference) {
        exigerConfiguration();

        Map<String, Object> reponse = get("/transaction/" + reference + "/");
        if (reponse == null || reponse.get("status") == null) {
            return Optional.empty();
        }

        return Optional.of(new EtatTransaction(
                texte(reponse, "reference"),
                texte(reponse, "status"),
                montant(reponse.get("amount")),
                texte(reponse, "operator"),
                texte(reponse, "operator_reference"),
                texte(reponse, "code")));
    }

    // -------------------------------------------------------------------------
    // Plomberie HTTP
    // -------------------------------------------------------------------------

    /**
     * Le jeton d'accès, mis en cache jusqu'à peu avant son expiration.
     *
     * <p>{@code synchronized} sur le renouvellement seulement : sans lui, dix
     * requêtes simultanées après expiration demanderaient dix jetons. Ce n'est
     * pas faux, mais c'est dix fois la latence pour rien.</p>
     */
    private String jeton() {
        String courant = jeton;
        if (courant != null && Instant.now().isBefore(jetonExpireLe)) {
            return courant;
        }

        synchronized (this) {
            // Une autre requête a pu renouveler pendant qu'on attendait le verrou.
            if (jeton != null && Instant.now().isBefore(jetonExpireLe)) {
                return jeton;
            }

            Map<String, Object> reponse;
            try {
                reponse = http.post()
                        .uri("/token/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("username", utilisateur, "password", motDePasse))
                        .retrieve()
                        .body(CARTE);
            } catch (RestClientException e) {
                // Le message de l'exception peut contenir le corps de la
                // requête, donc le mot de passe : on ne journalise QUE la classe.
                log.error("Authentification Campay impossible : {}", e.getClass().getSimpleName());
                throw new OperateurIndisponible(
                        "Le service de paiement est momentanément injoignable.");
            }

            String obtenu = reponse == null ? null : texte(reponse, "token");
            if (obtenu == null || obtenu.isBlank()) {
                throw new OperateurIndisponible(
                        "Le service de paiement a refusé nos identifiants.");
            }

            // Campay annonce une durée en secondes. Si elle manque, dix minutes :
            // court, donc sans risque, et le cache sert quand même.
            long secondes = entier(reponse.get("expires_in"), 600);

            jeton = obtenu;
            jetonExpireLe = Instant.now().plusSeconds(secondes).minus(MARGE_EXPIRATION);
            return obtenu;
        }
    }

    private Map<String, Object> post(String chemin, Map<String, Object> corps) {
        try {
            return http.post()
                    .uri(chemin)
                    .header("Authorization", "Token " + jeton())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corps)
                    .retrieve()
                    .body(CARTE);
        } catch (OperateurIndisponible e) {
            throw e;
        } catch (HttpClientErrorException refus) {
            throw refusDe(chemin, refus);
        } catch (RestClientException e) {
            log.error("Appel Campay {} injoignable : {}", chemin, e.getClass().getSimpleName());
            throw new OperateurIndisponible(
                    "Le service de paiement est momentanément injoignable.");
        }
    }

    /**
     * Traduit un refus de l'opérateur en message lisible.
     *
     * <p>⚠️ Le corps EST journalisé ici, contrairement à l'appel au jeton : une
     * requête de collecte ne contient aucun identifiant, et sans cette trace on
     * ne sait jamais pourquoi Campay a dit non.</p>
     *
     * <p>⚠️ On ne recopie pas le corps brut à l'écran : il est en anglais et
     * technique. On en tire ce qu'on sait nommer, et on retombe sur une phrase
     * générale sinon — mais une phrase qui dit bien « refusé », pas
     * « injoignable ».</p>
     */
    private OperateurRefuse refusDe(String chemin, HttpClientErrorException refus) {
        String corps = refus.getResponseBodyAsString();
        log.warn("Appel Campay {} refuse ({}) : {}", chemin, refus.getStatusCode(), corps);

        String bas = corps == null ? "" : corps.toLowerCase(java.util.Locale.ROOT);

        if (bas.contains("amount")) {
            return new OperateurRefuse(
                    "Le montant n'est pas accepté par l'opérateur. "
                    + "Le minimum est de " + MONTANT_MINIMUM + " FCFA.");
        }
        if (bas.contains("phone") || bas.contains("number") || bas.contains("from")) {
            return new OperateurRefuse(
                    "Ce numéro n'est pas accepté par l'opérateur. Vérifiez-le.");
        }
        return new OperateurRefuse(
                "L'opérateur a refusé ce paiement. Vérifiez le numéro et le montant.");
    }

    /**
     * Un {@code GET} qui renvoie {@code null} sur 404 plutôt que de lever.
     *
     * <p>« Cette référence n'existe pas chez Campay » est une réponse utile,
     * pas une panne : c'est exactement ce qu'on obtient quand un inconnu
     * invente une référence et l'envoie à notre webhook.</p>
     */
    private Map<String, Object> get(String chemin) {
        try {
            return http.get()
                    .uri(chemin)
                    .header("Authorization", "Token " + jeton())
                    .retrieve()
                    .onStatus(statut -> statut.value() == 404, (requete, reponse) -> {
                        // Volontairement vide : on veut null, pas une exception.
                    })
                    .body(CARTE);
        } catch (OperateurIndisponible e) {
            throw e;
        } catch (HttpClientErrorException refus) {
            throw refusDe(chemin, refus);
        } catch (RestClientException e) {
            log.error("Appel Campay {} injoignable : {}", chemin, e.getClass().getSimpleName());
            throw new OperateurIndisponible(
                    "Le service de paiement est momentanément injoignable.");
        }
    }

    private void exigerConfiguration() {
        if (!configure) {
            throw new OperateurIndisponible(
                    "Le paiement mobile money n'est pas configuré sur ce serveur.");
        }
    }

    private static String texte(Map<String, Object> carte, String cle) {
        Object valeur = carte.get(cle);
        return valeur == null ? null : String.valueOf(valeur);
    }

    private static BigDecimal montant(Object valeur) {
        if (valeur == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(valeur));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long entier(Object valeur, long defaut) {
        if (valeur == null) {
            return defaut;
        }
        try {
            return Long.parseLong(String.valueOf(valeur));
        } catch (NumberFormatException e) {
            return defaut;
        }
    }
}
