package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.FournisseurPaiement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * La passerelle vers GeniusPay (D-56).
 *
 * <p>Le même parcours que MoneyFusion : GARAH crée une session, le client paie
 * sur la page de GeniusPay, et GARAH redemande l'état. La documentation
 * officielle (docs.geniuspay.ci, version 2.0.0, lue le 12/10/2026) :</p>
 *
 * <pre>
 * POST {base}/payments                     {amount, currency, customer, ...}  → {data: {reference, payment_url}}
 * GET  {base}/payments/{reference}/status                                     → {data: {status, amount, payment_method}}
 * </pre>
 *
 * <h2>Ce qui change par rapport à MoneyFusion</h2>
 *
 * <ul>
 *   <li><b>Une clé secrète</b> ({@code sk_live_…}) dans l'en-tête
 *       {@code X-API-Key}, au lieu d'un lien secret. Elle ne quitte jamais
 *       le serveur : ni dans une application, ni dans un journal.</li>
 *   <li><b>Aucun filtre par adresse IP</b> annoncé : pas besoin du relais à
 *       IP fixe qu'exigeait MoneyFusion.</li>
 *   <li><b>La consultation d'état est authentifiée</b>, avec la même clé.</li>
 *   <li><b>Les notifications sont signées</b> (HMAC-SHA256 du corps brut).
 *       La signature reste une défense SUPPLÉMENTAIRE : comme pour les deux
 *       autres fournisseurs, une notification n'est qu'un signal, et l'état
 *       est redemandé ici.</li>
 * </ul>
 *
 * <p>⚠️ <b>Pas de Centrafrique</b> dans leur couverture publiée : Orange Money
 * et MTN au Cameroun seulement, côté CEMAC.</p>
 */
@Component
public class ClientGeniusPay implements PasserellePaiement {

    private static final Logger log = LoggerFactory.getLogger(ClientGeniusPay.class);

    private static final ParameterizedTypeReference<Map<String, Object>> CARTE =
            new ParameterizedTypeReference<>() {
            };

    /** La base de production documentée ; surchargeable s'ils la déplacent. */
    static final String BASE_PAR_DEFAUT = "https://geniuspay.ci/api/v1";

    private final RestClient http;
    private final String base;
    private final String cle;
    private final String secretWebhook;
    private final boolean configure;

    public ClientGeniusPay(@Value("${GARAH_GENIUSPAY_API_KEY:}") String cle,
                           @Value("${GARAH_GENIUSPAY_BASE_URL:}") String base,
                           @Value("${GARAH_GENIUSPAY_WEBHOOK_SECRET:}") String secretWebhook,
                           RestClient.Builder constructeur) {

        // ⚠️ Une variable VIDE n'est pas une variable ABSENTE (voir
        //    ClientCampay) : on teste le CONTENU.
        this.cle = cle == null ? "" : cle.strip();
        this.secretWebhook = secretWebhook == null ? "" : secretWebhook.strip();
        String b = base == null ? "" : base.strip().replaceFirst("/+$", "");
        this.base = b.isEmpty() ? BASE_PAR_DEFAUT : b;

        this.configure = this.cle.startsWith("sk_");
        this.http = constructeur.build();

        if (!this.cle.isEmpty() && !configure) {
            // Une clé publique (pk_…) collée à la place de la secrète, ou des
            // guillemets recopiés. On ne la journalise pas.
            log.error("GARAH_GENIUSPAY_API_KEY est posee mais ne commence pas par sk_ : "
                    + "ce n'est pas une cle secrete. GeniusPay est considere comme NON configure.");
        } else if (estDemonstration() && configure) {
            log.warn("GeniusPay est en BAC A SABLE (cle sk_sandbox_) : aucun encaissement reel.");
        }
        if (configure && this.secretWebhook.isEmpty()) {
            log.warn("GARAH_GENIUSPAY_WEBHOOK_SECRET est vide : les notifications GeniusPay "
                    + "ne seront pas authentifiees. Sans gravite : l'etat est toujours redemande.");
        }
    }

    @Override
    public FournisseurPaiement fournisseur() {
        return FournisseurPaiement.GENIUSPAY;
    }

    @Override
    public boolean estConfigure() {
        return configure;
    }

    /** Une clé {@code sk_sandbox_} n'encaisse rien de réel ; pas de clé non plus. */
    @Override
    public boolean estDemonstration() {
        return !configure || cle.startsWith("sk_sandbox_");
    }

    /**
     * Crée une session de paiement et renvoie l'adresse de sa page.
     *
     * <p>Pas de {@code payment_method} : GeniusPay propose alors lui-même les
     * moyens disponibles sur sa page, et le client choisit (D-55).</p>
     *
     * <p>🎯 <b>L'en-tête {@code Idempotency-Key} est dérivé de NOTRE numéro de
     * paiement</b>, toujours le même pour un même paiement. Une requête
     * rejouée après une coupure réseau ne crée donc pas un second débit :
     * GeniusPay reconnaît la clé.</p>
     */
    @Override
    public Collecte encaisser(DemandeEncaissement demande) {
        exigerConfiguration();

        // Le FCFA n'a pas de centimes (leur documentation le rappelle) :
        // setScale(0, UNNECESSARY) lève plutôt que d'arrondir en silence.
        long montant = demande.montant().setScale(0, RoundingMode.UNNECESSARY).longValueExact();

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("phone", numeroInternational(demande.telephone()));
        if (demande.nomClient() != null && !demande.nomClient().isBlank()) {
            client.put("name", demande.nomClient());
        }

        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("amount", montant);
        corps.put("currency", "XAF");
        corps.put("description", "GARAH, commande " + demande.numeroCommande());
        corps.put("customer", client);
        if (demande.urlRetour() != null && !demande.urlRetour().isBlank()) {
            corps.put("return_url", demande.urlRetour());
        }

        Map<String, Object> reponse;
        try {
            reponse = http.post()
                    .uri(base + "/payments")
                    .header("X-API-Key", cle)
                    .header("Idempotency-Key", cleIdempotence(demande.paiementId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(corps)
                    .retrieve()
                    .body(CARTE);
        } catch (HttpClientErrorException refus) {
            log.warn("GeniusPay a refuse la creation du paiement {} ({}) : {}",
                    demande.paiementId(), refus.getStatusCode(), refus.getResponseBodyAsString());
            throw refus(messageDe(refus.getResponseBodyAsString()));
        } catch (RestClientException e) {
            // ⚠️ Jamais le message de l'exception : il peut porter les en-têtes.
            log.error("GeniusPay injoignable a la creation du paiement {} : {}",
                    demande.paiementId(), e.getClass().getSimpleName());
            throw new OperateurIndisponible("Le service de paiement est momentanément injoignable.");
        }

        if (reponse == null || !vrai(reponse.get("success"))) {
            String message = reponse == null ? "" : texte(reponse, "message");
            log.warn("GeniusPay a refuse le paiement {} : {}", demande.paiementId(), message);
            throw refus(message);
        }

        Map<String, Object> data = donnees(reponse);
        String reference = data == null ? null : texte(data, "reference");
        String url = data == null ? null : texte(data, "payment_url");
        if (reference == null || reference.isBlank() || url == null || !url.startsWith("https://")) {
            // Sans référence, ce paiement ne pourra jamais être rapproché ;
            // sans page, le client ne peut pas payer.
            log.error("GeniusPay a accepte le paiement {} sans reference ou sans page de paiement",
                    demande.paiementId());
            throw new OperateurIndisponible("L'opérateur n'a pas renvoyé de page de paiement.");
        }

        return new Collecte(reference, null, null, url);
    }

    /**
     * L'état réel d'une transaction, redemandé à GeniusPay.
     *
     * <p>🎯 C'est lui qui décide, jamais la notification.</p>
     */
    @Override
    public Optional<EtatTransaction> statut(String reference) {
        exigerConfiguration();

        Map<String, Object> reponse;
        try {
            // ⚠️ La référence passe par un GABARIT : elle vient d'une
            //    notification que n'importe qui peut forger.
            reponse = http.get()
                    .uri(base + "/payments/{reference}/status", reference)
                    .header("X-API-Key", cle)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (requete, rep) -> {
                        // Volontairement vide : « inconnu » est une réponse.
                    })
                    .body(CARTE);
        } catch (HttpClientErrorException refus) {
            log.warn("GeniusPay refuse la lecture du paiement {} ({})", reference, refus.getStatusCode());
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("GeniusPay injoignable pour le paiement {} : {}",
                    reference, e.getClass().getSimpleName());
            throw new OperateurIndisponible("Le service de paiement est momentanément injoignable.");
        }

        Map<String, Object> data = reponse == null || !vrai(reponse.get("success")) ? null : donnees(reponse);
        if (data == null || data.get("status") == null) {
            return Optional.empty();
        }

        Issue issue = issueDe(texte(data, "status"));
        return Optional.of(new EtatTransaction(
                texte(data, "reference") == null ? reference : texte(data, "reference"),
                issue,
                montant(data.get("amount")),
                texte(data, "payment_method"),
                null,
                issue == Issue.ECHOUEE ? "ECHEC_GENIUSPAY" : null));
    }

    /**
     * La notification porte-t-elle une signature valide ?
     *
     * <p>HMAC-SHA256 du corps BRUT (avant toute lecture JSON), en hexadécimal,
     * avec le secret webhook ; comparaison en temps constant.</p>
     *
     * <p>⚠️ Faux s'il n'y a pas de secret ou pas de signature : on ne peut
     * rien affirmer. Ce n'est pas un refus — le contrôleur traite quand même,
     * puisque l'état est redemandé à GeniusPay.</p>
     */
    public boolean signatureValide(String corpsBrut, String signature) {
        if (secretWebhook.isEmpty() || signature == null || signature.isBlank() || corpsBrut == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretWebhook.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String attendu = HexFormat.of().formatHex(mac.doFinal(corpsBrut.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    attendu.getBytes(StandardCharsets.US_ASCII),
                    signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Le vocabulaire de GeniusPay : {@code pending}, {@code completed},
     * {@code failed}, {@code refunded}.
     *
     * <p>⚠️ {@code refunded} compte comme un échec : il ne peut toucher qu'un
     * paiement que nous attendions encore, donc jamais confirmé chez nous —
     * l'argent est reparti chez le client.</p>
     */
    static Issue issueDe(String statut) {
        if (statut == null) {
            return Issue.EN_COURS;
        }
        return switch (statut.strip().toLowerCase(Locale.ROOT)) {
            case "completed", "succeeded", "success", "paid" -> Issue.REUSSIE;
            case "failed", "cancelled", "canceled", "expired", "refunded" -> Issue.ECHOUEE;
            default -> Issue.EN_COURS;
        };
    }

    /** {@code +237699000000} : le format international qu'attend GeniusPay. */
    static String numeroInternational(String telephone) {
        String chiffres = telephone == null ? "" : telephone.replaceAll("[^0-9]", "");
        if (chiffres.startsWith("00")) {
            chiffres = chiffres.substring(2);
        }
        return "+" + chiffres;
    }

    /** Toujours la même pour un même paiement : c'est ce qui rend le rejeu sans effet. */
    static String cleIdempotence(Long paiementId) {
        return UUID.nameUUIDFromBytes(("garah-paiement-" + paiementId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static OperateurRefuse refus(String message) {
        // On RAPPORTE ce que l'opérateur a dit, on ne le devine pas (voir ClientCampay).
        return new OperateurRefuse(message == null || message.isBlank()
                ? "L'opérateur a refusé ce paiement."
                : "L'opérateur a refusé : " + message);
    }

    private static String messageDe(String corps) {
        if (corps == null || corps.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> lu = new com.fasterxml.jackson.databind.ObjectMapper().readValue(corps, Map.class);
            Object m = lu.get("message") != null ? lu.get("message") : lu.get("error");
            return m == null ? "" : String.valueOf(m).strip();
        } catch (Exception illisible) {
            return "";   // une page HTML d'erreur n'a rien à faire dans une bulle
        }
    }

    private void exigerConfiguration() {
        if (!configure) {
            throw new OperateurIndisponible(
                    "Le paiement GeniusPay n'est pas configuré sur ce serveur.");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> donnees(Map<String, Object> reponse) {
        return reponse.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : null;
    }

    private static boolean vrai(Object valeur) {
        return valeur instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(valeur));
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
}
