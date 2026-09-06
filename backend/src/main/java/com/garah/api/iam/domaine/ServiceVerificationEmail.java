package com.garah.api.iam.domaine;

import com.garah.api.commun.email.PasserelleEmail;
import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.infra.JetonVerificationEmailRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * La confirmation de l'adresse e-mail (D-23).
 *
 * <h2>Pourquoi c'est un sujet métier, pas seulement de sécurité</h2>
 *
 * <p>L'inscription créait un compte sans aucune vérification : n'importe qui
 * pouvait s'inscrire avec l'adresse d'un autre. Pour GARAH, ce n'est pas une
 * gêne théorique — le <b>suivi de commande</b>, le <b>code de retrait</b> et
 * les notifications d'acheminement partent tous à cette adresse (D-07).</p>
 *
 * <p>Une adresse fausse, et la marchandise arrive à Bangui sans que personne
 * ne puisse être prévenu. Le défaut ne se verrait pas à l'inscription : il se
 * verrait au retrait, devant le point de récupération.</p>
 *
 * <h2>Ce que « non vérifié » empêche, et ce qu'il n'empêche pas</h2>
 *
 * <pre>
 * se connecter, parcourir, remplir un panier   ✅ autorisé
 * passer une commande                          ❌ refusé
 * </pre>
 *
 * <p>Bloquer la <b>connexion</b> serait plus strict et plus mauvais : le
 * client ne pourrait même pas demander un nouveau lien, et le premier e-mail
 * perdu fermerait le compte définitivement. On barre au dernier moment utile,
 * celui où l'adresse commence réellement à servir.</p>
 */
@Service
public class ServiceVerificationEmail {

    private static final Logger log = LoggerFactory.getLogger(ServiceVerificationEmail.class);

    /** 32 octets tirés au sort — 256 bits, comme le jeton de rafraîchissement. */
    private static final int OCTETS_JETON = 32;

    private static final SecureRandom ALEA = new SecureRandom();

    /**
     * Combien de liens au maximum par compte et par heure.
     *
     * <p>Deuxième ligne de défense derrière la limitation de débit par IP :
     * celle-ci compte par <b>compte</b>. Un attaquant qui change d'adresse IP
     * ne peut toujours pas faire pleuvoir des e-mails sur une même victime —
     * ce qui s'appelle du harcèlement par formulaire, et se retourne contre
     * notre réputation d'expéditeur.</p>
     */
    private static final int RENVOIS_MAX_PAR_HEURE = 3;

    private final JetonVerificationEmailRepository jetons;
    private final UtilisateurRepository utilisateurs;
    private final PasserelleEmail emails;
    private final Duration validite;
    private final String baseUrl;
    private final String urlApi;

    public ServiceVerificationEmail(JetonVerificationEmailRepository jetons,
                                    UtilisateurRepository utilisateurs,
                                    PasserelleEmail emails,
                                    @Value("${GARAH_VERIFICATION_VALIDITE_HEURES:48}") long heures,
                                    @Value("${GARAH_URL_VERIFICATION:}") String baseUrl,
                                    @Value("${GARAH_URL_API:}") String urlApi) {
        this.jetons = jetons;
        this.utilisateurs = utilisateurs;
        this.emails = emails;
        this.validite = Duration.ofHours(heures);
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceFirst("/+$", "");
        this.urlApi = urlApi == null ? "" : urlApi.strip().replaceFirst("/+$", "");

        // ⚠️ Un lien relatif ne mène nulle part depuis une boîte mail. Si le
        // SMTP est actif et qu'aucune adresse absolue n'est connue, chaque
        // message partira avec un lien mort — et rien ne le signalerait, ni
        // côté serveur ni côté client.
        if (this.baseUrl.isBlank() && this.urlApi.isBlank() && emails.estConfigure()) {
            log.warn("Ni GARAH_URL_VERIFICATION ni GARAH_URL_API ne sont renseignes : "
                    + "les liens de confirmation seront RELATIFS, donc inutilisables "
                    + "dans un e-mail.");
        }
    }

    /**
     * Le lien est invalide, expiré, ou déjà utilisé.
     *
     * <p>Message <b>identique</b> dans les trois cas. Distinguer « ce jeton
     * n'existe pas » de « ce jeton a expiré » renseignerait sur ce qui a été
     * émis. Et pour l'utilisateur, la conduite à tenir est la même :
     * redemander un lien.</p>
     */
    public static class LienInvalide extends ErreurMetier {
        LienInvalide() {
            super("LIEN_INVALIDE",
                    "Ce lien de confirmation n'est plus valide. Demandez-en un nouveau.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.GONE;
        }
    }

    /** L'adresse n'est pas confirmée : {@code 403}, avec la marche à suivre. */
    public static class AdresseNonConfirmee extends ErreurMetier {
        public AdresseNonConfirmee() {
            super("ADRESSE_NON_CONFIRMEE",
                    "Confirmez votre adresse e-mail avant de commander. "
                    + "Un lien vous a été envoyé à l'inscription.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.FORBIDDEN;
        }
    }

    /**
     * Émet un jeton et envoie le lien.
     *
     * <p>⚠️ <b>L'envoi a lieu HORS de cette transaction</b>, dans
     * {@link #envoyerLien}. Un SMTP lent tiendrait sinon une connexion
     * PostgreSQL ouverte pendant plusieurs secondes — le pool Neon n'en a que
     * cinq (D-14) —, et un SMTP en panne annulerait le jeton qu'on vient
     * d'écrire.</p>
     *
     * @return le jeton EN CLAIR, à transmettre par e-mail et nulle part ailleurs
     */
    @Transactional
    public String emettre(Long utilisateurId, String adresse) {
        // Les jetons précédents sont invalidés : trois renvois ne doivent pas
        // laisser trois liens actifs, dont deux dans des boîtes qu'on ne
        // contrôle plus (un ancien e-mail transféré, une capture partagée).
        jetons.invaliderLesOuverts(utilisateurId, Instant.now());

        byte[] brut = new byte[OCTETS_JETON];
        ALEA.nextBytes(brut);
        String enClair = Base64.getUrlEncoder().withoutPadding().encodeToString(brut);

        jetons.save(new JetonVerificationEmail(
                utilisateurId, empreinte(enClair), adresse, Instant.now().plus(validite)));

        return enClair;
    }

    /**
     * Confirme une adresse à partir du jeton reçu par e-mail.
     *
     * <p>Trois contrôles, et le troisième est le moins évident :</p>
     * <ol>
     *   <li>le jeton existe et n'est ni expiré ni consommé ;</li>
     *   <li>l'utilisateur existe toujours ;</li>
     *   <li>🎯 <b>l'adresse du compte est encore celle visée à l'émission</b>.</li>
     * </ol>
     *
     * <p>Sans le troisième : je m'inscris avec mon adresse, je reçois le lien,
     * je change mon e-mail pour celui de quelqu'un d'autre, puis je clique — et
     * je viens de confirmer une adresse que je ne contrôle pas.</p>
     */
    @Transactional
    public Utilisateur confirmer(String jetonEnClair) {
        if (jetonEnClair == null || jetonEnClair.isBlank()) {
            throw new LienInvalide();
        }

        JetonVerificationEmail jeton = jetons.findByEmpreinte(empreinte(jetonEnClair))
                .orElseThrow(LienInvalide::new);

        if (!jeton.estUtilisable()) {
            throw new LienInvalide();
        }

        Utilisateur utilisateur = utilisateurs.findById(jeton.getUtilisateurId())
                .orElseThrow(LienInvalide::new);

        if (!utilisateur.getEmail().equalsIgnoreCase(jeton.getAdresseVisee())) {
            log.warn("Jeton de verification presente pour une adresse qui a change "
                    + "(utilisateur {}). Refuse.", utilisateur.getId());
            throw new LienInvalide();
        }

        jeton.consommer();
        utilisateur.marquerEmailVerifie();

        log.info("Adresse confirmee pour l'utilisateur {}", utilisateur.getId());
        return utilisateur;
    }

    /**
     * Renvoie un lien à un utilisateur déjà inscrit.
     *
     * <p>Répond sans erreur si l'adresse est <b>déjà</b> confirmée : redemander
     * un lien qu'on n'avait pas besoin de demander n'est pas une faute, et
     * répondre « déjà confirmée » à un inconnu renseignerait sur l'état d'un
     * compte.</p>
     */
    @Transactional
    public void renvoyer(Long utilisateurId) {
        Utilisateur utilisateur = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> RessourceIntrouvable.de("Utilisateur", utilisateurId));

        if (utilisateur.estEmailVerifie()) {
            return;
        }

        long emisRecemment = jetons.comptesDepuis(
                utilisateurId, Instant.now().minus(Duration.ofHours(1)));

        if (emisRecemment >= RENVOIS_MAX_PAR_HEURE) {
            throw new RegleMetierViolee("TROP_DE_RENVOIS",
                    "Trop de demandes. Réessayez dans une heure, "
                    + "et pensez à regarder vos courriers indésirables.");
        }

        String jeton = emettre(utilisateurId, utilisateur.getEmail());
        envoyerLien(utilisateur.getEmail(), utilisateur.getNom(), jeton);
    }

    /**
     * Envoie le message. <b>À appeler hors transaction.</b>
     *
     * <p>Ne lève jamais : {@link PasserelleEmail} renvoie un booléen. Un envoi
     * raté laisse un compte valide, et le client peut redemander un lien.
     * Faire échouer l'inscription pour un SMTP indisponible serait absurde.</p>
     */
    public void envoyerLien(String adresse, String nom, String jetonEnClair) {
        String lien = lienDe(jetonEnClair);

        boolean parti = emails.envoyer(adresse, "Confirmez votre adresse — GARAH",
                corpsDuMessage(nom, lien));

        if (!parti) {
            // Pas une erreur pour l'appelant : le compte existe, le jeton
            // aussi, et le client peut demander un renvoi.
            log.warn("Lien de verification non transmis a l'utilisateur ; "
                    + "un renvoi sera necessaire.");
        }
    }

    /**
     * L'URL sur laquelle pointe le lien.
     *
     * <p>⚠️ <b>Elle doit être ABSOLUE.</b> Un lien relatif
     * ({@code /api/auth/verification?jeton=…}) est parfaitement valide dans une
     * page web et totalement inutilisable dans un e-mail : le client de
     * messagerie n'a aucune origine sur laquelle le résoudre. C'était le cas de
     * la première version — le message partait, et le lien ne menait nulle
     * part.</p>
     *
     * <p>Deux variables, dans cet ordre de priorité :</p>
     * <ol>
     *   <li>{@code GARAH_URL_VERIFICATION} — la page du frontend, le jour où
     *       elle existera ;</li>
     *   <li>{@code GARAH_URL_API} — l'adresse publique de l'API, qui sert
     *       elle-même une page de confirmation. Le flux est ainsi complet
     *       <b>sans frontend</b>.</li>
     * </ol>
     *
     * <p>Sans l'une ni l'autre, on retombe sur un lien relatif — utilisable
     * uniquement quand le message finit dans les journaux, en développement.
     * Le constructeur avertit dans ce cas.</p>
     */
    String lienDe(String jeton) {
        String encode = URLEncoder.encode(jeton, StandardCharsets.UTF_8);

        if (!baseUrl.isBlank()) {
            return baseUrl + "?jeton=" + encode;
        }
        if (!urlApi.isBlank()) {
            return urlApi + "/api/auth/verification?jeton=" + encode;
        }
        return "/api/auth/verification?jeton=" + encode;
    }

    /**
     * Le message.
     *
     * <p>Volontairement sobre et sans image : un e-mail chargé de balises et
     * de ressources distantes finit en courrier indésirable, et c'est le pire
     * résultat possible pour un lien de confirmation.</p>
     */
    private String corpsDuMessage(String nom, String lien) {
        return """
               <p>Bonjour %s,</p>
               <p>Confirmez votre adresse e-mail pour pouvoir passer commande sur GARAH :</p>
               <p><a href="%s">Confirmer mon adresse</a></p>
               <p>Ou copiez ce lien dans votre navigateur :<br>%s</p>
               <p>Ce lien est valable %d heures. Si vous n'êtes pas à l'origine de
               cette inscription, ignorez ce message : aucun compte ne sera utilisable
               sans cette confirmation.</p>
               <p>— L'équipe GARAH</p>
               """.formatted(nom, lien, lien, validite.toHours());
    }

    /** Purge les jetons expirés. Appelée par la tâche périodique. */
    @Transactional
    public int purger() {
        return jetons.purger(Instant.now().minus(Duration.ofDays(7)));
    }

    /** Le compte a-t-il confirmé son adresse ? */
    @Transactional(readOnly = true)
    public boolean estConfirme(Long utilisateurId) {
        return utilisateurs.findById(utilisateurId)
                .map(Utilisateur::estEmailVerifie)
                .orElse(false);
    }

    /**
     * SHA-256, en hexadécimal — pas BCrypt.
     *
     * <p>Même raisonnement qu'au chapitre du rafraîchissement : ce jeton est
     * 256 bits tirés au sort, aucun dictionnaire n'existe contre lui. BCrypt
     * pour ce qu'un <i>humain</i> choisit, hachage rapide pour ce que la
     * <i>machine</i> tire au sort.</p>
     */
    static String empreinte(String enClair) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(enClair.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 introuvable", e);
        }
    }

    /** Utile aux tests : retrouver un jeton émis sans passer par l'e-mail. */
    Optional<JetonVerificationEmail> parEmpreinte(String enClair) {
        return jetons.findByEmpreinte(empreinte(enClair));
    }
}
