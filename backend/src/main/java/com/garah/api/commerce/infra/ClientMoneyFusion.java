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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * La passerelle vers MoneyFusion (D-55).
 *
 * <h2>Ce qui change par rapport à Campay</h2>
 *
 * <p>Campay fait sonner le téléphone du client. MoneyFusion, lui, renvoie
 * l'adresse d'une <b>page de paiement</b> : le client y choisit MTN ou Orange,
 * y saisit son numéro, et valide. GARAH ne voit rien de ce qui s'y passe.</p>
 *
 * <pre>
 * POST {lien du tableau de bord}      {totalPrice, numeroSend, ...}  → {token, url}
 * GET  {base statut}/{token}                                         → {data: {statut, Montant, ...}}
 * </pre>
 *
 * <h2>🎯 Le lien d'API EST le secret</h2>
 *
 * <p>MoneyFusion ne donne ni identifiant ni mot de passe : il génère, dans le
 * tableau de bord, une adresse propre à l'application GARAH. Quiconque la
 * connaît peut créer des paiements à notre nom. Elle vit donc dans une
 * variable d'environnement, et n'est jamais journalisée.</p>
 *
 * <p>⚠️ <b>MoneyFusion n'accepte que les IP déclarées.</b> Un appel depuis une
 * adresse inconnue est refusé avec un message qui nomme l'IP fautive. Sur
 * Azure, l'application a PLUSIEURS adresses sortantes possibles : il faut les
 * déclarer toutes, sinon le paiement marche une fois sur deux.</p>
 *
 * <p>⚠️ <b>La consultation d'état n'exige aucune authentification</b> chez
 * MoneyFusion : qui connaît un jeton peut lire sa transaction. C'est leur
 * choix, pas le nôtre. Pour nous, la conséquence est la même qu'avec Campay :
 * le webhook n'est qu'un signal, et on redemande l'état ici.</p>
 */
@Component
public class ClientMoneyFusion implements PasserellePaiement {

    private static final Logger log = LoggerFactory.getLogger(ClientMoneyFusion.class);

    private static final ParameterizedTypeReference<Map<String, Object>> CARTE =
            new ParameterizedTypeReference<>() {
            };

    /** Documentée par MoneyFusion ; surchargeable s'ils la déplacent. */
    static final String URL_STATUT_PAR_DEFAUT = "https://pay.moneyfusion.net/paiementNotif/";

    private final RestClient http;
    private final String urlApi;
    private final String urlStatut;
    private final String urlWebhook;
    private final boolean configure;

    public ClientMoneyFusion(@Value("${GARAH_MONEYFUSION_API_URL:}") String urlApi,
                             @Value("${GARAH_MONEYFUSION_URL_STATUT:}") String urlStatut,
                             @Value("${GARAH_MONEYFUSION_WEBHOOK_URL:}") String urlWebhook,
                             RestClient.Builder constructeur) {

        this.urlApi = urlApi == null ? "" : urlApi.strip();
        this.urlWebhook = urlWebhook == null ? "" : urlWebhook.strip();

        // ⚠️ Une variable VIDE n'est pas une variable ABSENTE (voir
        //    ClientCampay) : on teste le CONTENU.
        String statut = urlStatut == null ? "" : urlStatut.strip();
        if (statut.isEmpty()) {
            statut = URL_STATUT_PAR_DEFAUT;
        }
        this.urlStatut = statut.endsWith("/") ? statut : statut + "/";

        this.configure = this.urlApi.startsWith("https://");
        this.http = constructeur.build();

        if (!this.urlApi.isEmpty() && !configure) {
            // Le lien est posé mais n'est pas une adresse HTTPS : faute de
            // frappe, guillemets recopiés, http:// en clair. On ne le
            // journalise pas — c'est le secret.
            log.error("GARAH_MONEYFUSION_API_URL est posee mais n'est pas une adresse https:// "
                    + "valide. MoneyFusion est considere comme NON configure.");
        }
        if (configure && this.urlWebhook.isEmpty()) {
            log.warn("GARAH_MONEYFUSION_WEBHOOK_URL est vide : les paiements MoneyFusion ne "
                    + "seront confirmes que par la reconciliation periodique (2 min).");
        }
    }

    @Override
    public FournisseurPaiement fournisseur() {
        return FournisseurPaiement.MONEYFUSION;
    }

    @Override
    public boolean estConfigure() {
        return configure;
    }

    /**
     * MoneyFusion n'a pas de bac à sable : configuré, il encaisse pour de bon.
     * Non configuré, rien n'est encaissé — ce qui compte aussi comme
     * démonstration pour l'écran.
     */
    @Override
    public boolean estDemonstration() {
        return !configure;
    }

    /**
     * Crée une session de paiement et renvoie l'adresse de sa page.
     *
     * <p>⚠️ Le montant part tel que la commande le fixe. Le client ne peut pas
     * le modifier sur la page MoneyFusion : c'est notre serveur qui l'a
     * transmis, avec le lien secret.</p>
     */
    @Override
    public Collecte encaisser(DemandeEncaissement demande) {
        exigerConfiguration();

        // ⚠️ Le XAF n'a pas de centimes. setScale(0, UNNECESSARY) lève sur une
        //    partie décimale non nulle : une erreur ici vaut mieux qu'un débit
        //    arrondi en silence.
        long montant = demande.montant().setScale(0, RoundingMode.UNNECESSARY).longValueExact();

        Map<String, Object> corps = new java.util.LinkedHashMap<>();
        corps.put("totalPrice", montant);
        // Un seul « article » : la commande. Le détail des lignes reste chez
        // nous ; MoneyFusion n'en a besoin que pour l'afficher.
        corps.put("article", List.of(Map.of("Commande " + demande.numeroCommande(), montant)));
        corps.put("numeroSend", numeroNational(demande.telephone()));
        corps.put("nomclient", demande.nomClient() == null || demande.nomClient().isBlank()
                ? "Client GARAH" : demande.nomClient());
        // Ce que MoneyFusion nous rendra dans le statut et le webhook : de
        // quoi rapprocher une ligne de leur tableau de bord d'une des nôtres.
        corps.put("personal_Info", List.of(Map.of(
                "paiementId", demande.paiementId(),
                "commandeId", demande.commandeId())));
        if (demande.urlRetour() != null && !demande.urlRetour().isBlank()) {
            corps.put("return_url", demande.urlRetour());
        }
        if (!urlWebhook.isEmpty()) {
            corps.put("webhook_url", urlWebhook);
        }

        Map<String, Object> reponse;
        try {
            reponse = http.post()
                    .uri(urlApi)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corps)
                    .retrieve()
                    .body(CARTE);
        } catch (HttpClientErrorException refus) {
            throw refusDe(refus.getStatusCode().value(), refus.getResponseBodyAsString());
        } catch (RestClientException e) {
            // ⚠️ Jamais le message de l'exception : il contient l'URL, donc
            //    le lien secret.
            log.error("MoneyFusion injoignable a la creation du paiement {} : {}",
                    demande.paiementId(), e.getClass().getSimpleName());
            throw new OperateurIndisponible("Le service de paiement est momentanément injoignable.");
        }

        if (reponse == null || !vrai(reponse.get("statut"))) {
            // MoneyFusion répond parfois 200 avec « statut: false » : c'est un
            // refus, et son message dit pourquoi (IP non déclarée, application
            // non validée…).
            String message = reponse == null ? "" : texte(reponse, "message");
            log.warn("MoneyFusion a refuse le paiement {} : {}", demande.paiementId(), message);
            throw refus(message);
        }

        String jeton = texte(reponse, "token");
        String url = texte(reponse, "url");
        if (jeton == null || jeton.isBlank() || url == null || !url.startsWith("https://")) {
            // Sans jeton, ce paiement ne pourra JAMAIS être rapproché. Sans
            // page, le client ne peut pas payer. Dans les deux cas, mieux vaut
            // échouer maintenant qu'avec un client devant une page blanche.
            log.error("MoneyFusion a accepte le paiement {} sans jeton ou sans page de paiement",
                    demande.paiementId());
            throw new OperateurIndisponible(
                    "L'opérateur n'a pas renvoyé de page de paiement.");
        }

        return new Collecte(jeton, null, null, url);
    }

    /**
     * L'état réel d'une transaction, redemandé à MoneyFusion.
     *
     * <p>🎯 C'est elle qui décide, jamais le webhook.</p>
     */
    @Override
    public Optional<EtatTransaction> statut(String reference) {
        exigerConfiguration();

        Map<String, Object> reponse;
        try {
            // ⚠️ La référence passe par un GABARIT, jamais par concaténation :
            //    elle vient d'une notification que n'importe qui peut forger,
            //    et « ../../autre-chose » ne doit pas changer la route visée.
            reponse = http.get()
                    .uri(urlStatut + "{jeton}", reference)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (requete, rep) -> {
                        // Volontairement vide : « inconnu » est une réponse.
                    })
                    .body(CARTE);
        } catch (HttpClientErrorException refus) {
            log.warn("MoneyFusion refuse la lecture du jeton {} ({})", reference, refus.getStatusCode());
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("MoneyFusion injoignable pour le jeton {} : {}",
                    reference, e.getClass().getSimpleName());
            throw new OperateurIndisponible("Le service de paiement est momentanément injoignable.");
        }

        if (reponse == null || !vrai(reponse.get("statut"))
                || !(reponse.get("data") instanceof Map<?, ?> brut)) {
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) brut;

        String statut = texte(data, "statut");
        BigDecimal montant = montant(data.get("Montant"));
        BigDecimal frais = montant(data.get("frais"));

        return Optional.of(new EtatTransaction(
                texte(data, "tokenPay") == null ? reference : texte(data, "tokenPay"),
                issueDe(statut),
                montantPaye(montant, frais),
                texte(data, "moyen"),
                texte(data, "numeroTransaction"),
                issueDe(statut) == Issue.ECHOUEE ? "ECHEC_MONEYFUSION" : null));
    }

    // -------------------------------------------------------------------------

    /**
     * Le vocabulaire de MoneyFusion : {@code paid}, {@code failure},
     * {@code no paid}, {@code pending}.
     *
     * <p>⚠️ {@code no paid} n'est PAS un échec : la documentation le décrit
     * comme « paiement non effectué », sans dire s'il est définitif. Le
     * traiter en échec annulerait le paiement d'un client encore sur la page.
     * On le laisse en cours ; le délai d'abandon le clôt s'il n'aboutit pas.</p>
     */
    static Issue issueDe(String statut) {
        if (statut == null) {
            return Issue.EN_COURS;
        }
        return switch (statut.strip().toLowerCase(Locale.ROOT)) {
            case "paid" -> Issue.REUSSIE;
            case "failure", "failed", "cancelled", "canceled" -> Issue.ECHOUEE;
            default -> Issue.EN_COURS;
        };
    }

    /**
     * Ce que le client a réellement payé.
     *
     * <p>⚠️ <b>À confirmer au premier paiement réel.</b> La documentation
     * montre {@code Montant: 200, frais: 5} dans un exemple et
     * {@code Montant: 194, frais: 6} dans un autre : on ne sait pas si
     * {@code Montant} est le brut ou le net après frais. Si c'est le net,
     * comparer {@code Montant} seul au prix de la commande ferait échouer
     * CHAQUE paiement réussi.</p>
     *
     * <p>On additionne donc les frais. Le risque accepté est borné : le montant
     * de la session est fixé par NOTRE serveur à sa création, le client ne
     * peut pas le changer ; le contrôle sert à attraper une référence
     * rapprochée de travers, et une erreur de cet ordre dépasse de loin les
     * frais.</p>
     */
    static BigDecimal montantPaye(BigDecimal montant, BigDecimal frais) {
        if (montant == null) {
            return null;
        }
        return frais == null ? montant : montant.add(frais);
    }

    /**
     * Le numéro au format national : {@code 699000000}, sans indicatif.
     *
     * <p>Les exemples de MoneyFusion n'ont jamais d'indicatif. Le numéro sert à
     * identifier le client dans leur tableau de bord ; c'est sur la page de
     * paiement qu'il saisit celui qui sera débité.</p>
     */
    static String numeroNational(String telephone) {
        String chiffres = telephone == null ? "" : telephone.replaceAll("[^0-9]", "");
        if (chiffres.startsWith("00")) {
            chiffres = chiffres.substring(2);
        }
        if (chiffres.length() == 12 && chiffres.startsWith("237")) {
            chiffres = chiffres.substring(3);
        }
        // Centrafrique : 236 + 8 chiffres.
        if (chiffres.length() == 11 && chiffres.startsWith("236")) {
            chiffres = chiffres.substring(3);
        }
        return chiffres;
    }

    private OperateurRefuse refusDe(int code, String corps) {
        log.warn("MoneyFusion a refuse la creation ({}) : {}", code, corps);
        String message = "";
        if (corps != null && !corps.isBlank()) {
            try {
                Object lu = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(corps, Map.class).get("message");
                message = lu == null ? "" : String.valueOf(lu).strip();
            } catch (Exception illisible) {
                // Une page HTML d'erreur n'a rien à faire dans une bulle.
            }
        }
        return refus(message);
    }

    /** On RAPPORTE ce que l'opérateur a dit, on ne le devine pas (voir ClientCampay). */
    private static OperateurRefuse refus(String message) {
        return new OperateurRefuse(message == null || message.isBlank()
                ? "L'opérateur a refusé ce paiement."
                : "L'opérateur a refusé : " + message);
    }

    private void exigerConfiguration() {
        if (!configure) {
            throw new OperateurIndisponible(
                    "Le paiement MoneyFusion n'est pas configuré sur ce serveur.");
        }
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
