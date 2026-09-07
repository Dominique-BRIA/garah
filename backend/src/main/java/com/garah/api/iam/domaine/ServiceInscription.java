package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

/**
 * La création d'un compte client — la porte d'entrée de toute la plateforme.
 *
 * <p>D-07 impose un compte pour commander. Sans cette classe, aucun client ne
 * peut exister autrement que par un {@code INSERT} manuel : le tunnel de vente
 * entier est inatteignable.</p>
 *
 * <p><b>Un client n'est pas un utilisateur, c'est un utilisateur PLUS une
 * ligne {@code client}.</b> Les deux naissent dans la même transaction. Créer
 * l'un sans l'autre produirait un compte qui se connecte mais ne peut ni
 * commander ni réclamer — et le défaut ne se verrait qu'au moment du paiement,
 * c'est-à-dire trop tard.</p>
 *
 * <p>⚠️ Lire {@link #inscrire} avant d'y toucher : le découpage des
 * transactions y est contraint par le journal de sécurité, et le remettre en
 * {@code @Transactional} casse l'inscription.</p>
 */
@Service
public class ServiceInscription {

    /**
     * Longueur minimale d'un mot de passe.
     *
     * <p>Volontairement une longueur, et <b>aucune</b> règle de composition
     * (« une majuscule, un chiffre, un caractère spécial »). Ces règles
     * produisent {@code Password1!} — court, prévisible, et présent dans
     * toutes les listes d'attaque. La longueur est ce qui coûte réellement
     * cher à un attaquant.</p>
     */
    /**
     * ⚠️ Volontairement visible dans le paquetage, et non {@code private}.
     *
     * <p>{@link ServiceProfil} applique la <b>même</b> exigence au changement
     * de mot de passe. Recopier le nombre là-bas produirait deux règles de
     * sécurité qui divergeraient au premier durcissement : on remonterait
     * l'une à douze, et l'inscription continuerait d'accepter dix sans que
     * rien ne le signale.</p>
     */
    static final int LONGUEUR_MOT_DE_PASSE_MIN = 10;

    /** Les langues du référentiel (D-08 / D-09). */
    private static final Set<String> LANGUES = Set.of("fr", "en", "sg");

    private final UtilisateurRepository utilisateurs;
    private final ClientRepository clients;
    private final PasswordEncoder encodeur;
    private final ServiceAuthentification authentification;
    private final ServiceVerificationEmail verification;

    /**
     * La transaction de création, pilotée à la main.
     *
     * <p>Voir {@link #inscrire} : {@code @Transactional} sur la méthode entière
     * produisait une violation de clé étrangère, parce que le journal de
     * sécurité s'écrit en {@code REQUIRES_NEW} et ne voyait pas l'utilisateur
     * non encore commis.</p>
     */
    private final TransactionTemplate transaction;

    public ServiceInscription(UtilisateurRepository utilisateurs,
                              ClientRepository clients,
                              PasswordEncoder encodeur,
                              ServiceAuthentification authentification,
                              ServiceVerificationEmail verification,
                              PlatformTransactionManager transactions) {
        this.utilisateurs = utilisateurs;
        this.clients = clients;
        this.encodeur = encodeur;
        this.authentification = authentification;
        this.verification = verification;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * L'adresse est déjà prise.
     *
     * <p>⚠️ <b>Ici, et seulement ici, on dit la vérité.</b> Le formulaire de
     * connexion, lui, reste volontairement flou (voir
     * {@link ServiceAuthentification.IdentifiantsInvalides}) pour ne pas
     * devenir un annuaire.</p>
     *
     * <p>L'incohérence n'est qu'apparente : un formulaire d'inscription <b>ne
     * peut pas</b> cacher qu'une adresse est prise, puisqu'il doit refuser le
     * doublon. Prétendre le contraire créerait un compte fantôme ou afficherait
     * « inscription réussie » sans rien créer — et la personne ne pourrait
     * jamais se connecter.</p>
     *
     * <p>Ce que ça coûte : l'inscription permet bien d'énumérer les adresses.
     * La contre-mesure n'est pas le silence, c'est la <b>limitation de débit</b>
     * sur cette route — à mettre en place avant l'ouverture au public.</p>
     */
    public static class AdresseDejaUtilisee extends ErreurMetier {
        AdresseDejaUtilisee() {
            super("EMAIL_DEJA_UTILISE",
                    "Un compte existe déjà avec cette adresse e-mail.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.CONFLICT;
        }
    }

    public static class MotDePasseTropFaible extends ErreurMetier {
        MotDePasseTropFaible() {
            super("MOT_DE_PASSE_TROP_FAIBLE",
                    "Le mot de passe doit contenir au moins "
                            + LONGUEUR_MOT_DE_PASSE_MIN + " caractères.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /**
     * Inscrit un client, puis le connecte.
     *
     * <p>On renvoie un {@link ResultatConnexion}, pas un simple « créé » :
     * demander à quelqu'un de ressaisir ses identifiants trois secondes après
     * les avoir choisis est une perte de conversion gratuite. Le jeton est
     * fabriqué par le <b>même</b> chemin que la connexion normale — aucune
     * seconde fabrique de jeton, donc aucune divergence possible.</p>
     *
     * <h2>⚠️ Pourquoi cette méthode n'est PAS {@code @Transactional}</h2>
     *
     * <p>Elle l'était, et c'était un bug — trouvé par le test, pas par la
     * relecture. Le mécanisme :</p>
     *
     * <pre>
     * inscrire()                    ouvre une transaction T1
     *   INSERT utilisateur          écrit dans T1, PAS ENCORE COMMIS
     *   connecter()
     *     enregistrer(événement)    REQUIRES_NEW → ouvre T2
     *       INSERT evenement_securite (utilisateur_id = …)
     *                               ❌ T2 ne voit pas la ligne de T1
     *                               → violation de clé étrangère
     * </pre>
     *
     * <p>Le journal de sécurité est volontairement écrit en
     * {@code REQUIRES_NEW} pour <b>survivre</b> à l'annulation de ce qu'il
     * journalise (chapitre 08 §9). Cette qualité se retourne ici : la
     * transaction isolée ne peut pas référencer une ligne qui n'existe pas
     * encore pour elle.</p>
     *
     * <p>La création est donc <b>commise d'abord</b>, dans sa propre
     * transaction, et la connexion vient après. Les deux restent atomiques
     * chacune de leur côté : {@code utilisateur} et {@code client} naissent
     * ensemble ou pas du tout.</p>
     *
     * <p>Ce que ça coûte : si la connexion échouait juste après, le compte
     * existerait sans que l'inscrit ait son jeton. Il lui suffirait de se
     * connecter. C'est infiniment préférable à l'inverse — un jeton pour un
     * compte qui n'a pas été créé.</p>
     *
     * @param adresseIp l'adresse réelle de l'inscrit, pour la surveillance
     */
    public ResultatConnexion inscrire(String email, String motDePasse, String nom,
                                      String prenom, String telephone, String langue,
                                      String adresseIp) {

        String adresse = email == null ? "" : email.strip();

        if (motDePasse == null || motDePasse.length() < LONGUEUR_MOT_DE_PASSE_MIN) {
            throw new MotDePasseTropFaible();
        }

        Long utilisateurId = creerLeCompte(adresse, motDePasse, nom, prenom, telephone, langue);

        // ⚠️ L'e-mail part APRÈS la transaction, jamais dedans (D-23).
        //
        // Trois raisons, et la dernière est la plus coûteuse :
        //   - un SMTP lent tiendrait une connexion PostgreSQL ouverte plusieurs
        //     secondes, et le pool Neon n'en a que cinq (D-14) ;
        //   - un SMTP en panne annulerait la création du compte, alors que
        //     celui-ci est parfaitement valide ;
        //   - le jeton doit être COMMIS avant d'être envoyé. Envoyé depuis la
        //     transaction, il arriverait chez le client avant d'exister en
        //     base — et le premier clic tomberait sur « lien invalide ».
        String jeton = verification.emettre(utilisateurId, adresse);
        verification.envoyerLien(adresse, nom.strip(), jeton);

        // Aucun événement de sécurité n'est écrit ici : l'appel ci-dessous en
        // produit déjà un (CONNEXION_REUSSIE). En écrire un second donnerait
        // deux lignes pour un seul fait, et fausserait le score de risque, qui
        // compte les connexions.
        //
        // Le jeton est fabriqué par le chemin normal de connexion : un client
        // n'a aucune permission (son accès repose sur la PROPRIÉTÉ de ses
        // données), et c'est exactement ce que ce chemin produit.
        return authentification.connecter(adresse, motDePasse, adresseIp);
    }

    /**
     * Crée l'utilisateur et sa ligne client, <b>ensemble</b>.
     *
     * <p>Une transaction explicite plutôt que {@code @Transactional} : appelée
     * depuis la même classe, l'annotation ne passerait pas par le proxy Spring
     * et serait purement décorative. Le piège est silencieux — le code
     * compile, et l'atomicité n'existe pas.</p>
     *
     * <p>{@code ServiceStatistiques} utilise le même outil, pour une raison
     * voisine : maîtriser explicitement les limites d'une transaction.</p>
     */
    private Long creerLeCompte(String adresse, String motDePasse, String nom,
                               String prenom, String telephone, String langue) {

        return transaction.execute(statut -> {
            // Vérification AVANT l'insertion, pour un message clair. L'index
            // unique sur lower(email) reste la vraie garantie : deux
            // inscriptions simultanées passeraient toutes deux ce test, et la
            // base en refusera une (traduite en 409 par le gestionnaire global).
            if (utilisateurs.existsByEmailIgnoreCase(adresse)) {
                throw new AdresseDejaUtilisee();
            }

            Utilisateur utilisateur = new Utilisateur(
                    TypeUtilisateur.CLIENT, nom.strip(), adresse, encodeur.encode(motDePasse));

            utilisateur.setPrenom(vide(prenom) ? null : prenom.strip());
            utilisateur.setTelephone(vide(telephone) ? null : telephone.strip());
            utilisateur.setLangue(langueValide(langue));

            utilisateurs.save(utilisateur);

            // ⚠️ Un utilisateur SANS ligne client se connecterait normalement,
            // puis échouerait au premier ajout au panier — et le défaut ne se
            // verrait qu'au moment de payer. Les deux naissent ensemble.
            clients.save(new Client(utilisateur, genererCodeClient()));

            return utilisateur.getId();
        });
    }

    /** {@code CLI-000042}, tiré d'une séquence PostgreSQL (V20). */
    private String genererCodeClient() {
        return "CLI-%06d".formatted(clients.prochainCode());
    }

    /**
     * Une langue inconnue retombe sur le français.
     *
     * <p>Refuser serait plus strict, mais {@code langue} est un confort
     * d'affichage : faire échouer une inscription pour un code de langue
     * exotique envoyé par un frontend mal configuré serait absurde. La clé
     * étrangère vers {@code langue} refuserait de toute façon la valeur.</p>
     */
    // Visible dans le paquetage : ServiceProfil applique la MÊME règle quand
    // on change sa langue depuis son profil. Deux copies divergeraient le jour
    // où une quatrième langue arrive.
    static String langueValide(String langue) {
        if (langue == null) {
            return "fr";
        }
        String code = langue.strip().toLowerCase();
        return LANGUES.contains(code) ? code : "fr";
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}
