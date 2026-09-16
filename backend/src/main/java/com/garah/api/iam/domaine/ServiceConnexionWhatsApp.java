package com.garah.api.iam.domaine;

import com.garah.api.commun.debit.LimiteurDebit;
import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.CodeConnexionRepository;
import com.garah.api.iam.infra.IdentiteSocialeRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.surveillance.domaine.GraviteEvenement;
import com.garah.api.surveillance.domaine.ServiceEvenementsSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * « Continuer avec WhatsApp » — numéro de téléphone et code à usage unique.
 *
 * <h2>⚠️ Lire ceci avant de modifier quoi que ce soit</h2>
 *
 * <p>Avec Google, <b>Google</b> atteste l'identité et nous vérifions une
 * signature : la sécurité est chez eux. Ici, <b>GARAH est le fournisseur
 * d'identité</b>. Chaque garde-fou de cette classe est ce qui sépare une
 * connexion sûre d'une porte ouverte :</p>
 *
 * <pre>
 * code hache              une fuite de la table ne donne aucun code
 * expiration 5 min        un code intercepte ne sert pas le lendemain
 * 5 tentatives            six chiffres se devinent sinon en quelques minutes
 * un seul code vivant     sinon on multiplie les chances, et les messages
 * reponse constante       le numero inconnu et le numero connu repondent pareil
 * limitation de debit     sinon un script produit une facture Meta
 * </pre>
 *
 * <h2>Ce qu'un compte né ici NE PEUT PAS encore faire</h2>
 *
 * <p>Il n'a <b>aucune adresse e-mail</b>. Or D-23 exige une adresse confirmée
 * pour commander. Un tel compte peut donc parcourir le catalogue et remplir
 * son panier, mais devra ajouter une adresse avant de payer.</p>
 *
 * <p>🎯 C'est une limite réelle, pas un oubli : WhatsApp atteste un
 * <b>numéro</b>, jamais une adresse. Aucune mécanique ne peut en inventer une.
 * Le gain du bouton est d'entrer sans mot de passe, pas de supprimer l'étape de
 * l'adresse.</p>
 */
@Service
public class ServiceConnexionWhatsApp {

    private static final Logger log = LoggerFactory.getLogger(ServiceConnexionWhatsApp.class);

    /**
     * ⚠️ {@link SecureRandom}, et surtout pas {@link java.util.Random}.
     *
     * <p>{@code Random} est prévisible : quelques tirages observés suffisent à
     * calculer les suivants. Un code de connexion engendré ainsi se devine sans
     * jamais rien intercepter.</p>
     */
    private static final SecureRandom HASARD = new SecureRandom();

    /** Un numéro ne peut pas demander plus de 3 codes par quart d'heure. */
    private static final int DEMANDES_MAX = 3;
    private static final Duration FENETRE = Duration.ofMinutes(15);

    private final CodeConnexionRepository codes;
    private final IdentiteSocialeRepository identites;
    private final UtilisateurRepository utilisateurs;
    private final ClientRepository clients;
    private final ServiceAuthentification authentification;
    private final ServiceEvenementsSecurite securite;
    private final EnvoiWhatsApp envoi;
    private final PasswordEncoder encodeur;
    private final TransactionTemplate transaction;

    /** Indépendante : elle commet même quand l appelante est annulee. */
    private final TransactionTemplate transactionIsolee;

    /**
     * Le compteur de demandes, par numéro.
     *
     * <p>Distinct de la limitation par IP posée en D-24 : celle-ci protège le
     * serveur, celui-là protège <b>le téléphone de quelqu'un d'autre</b>. Un
     * attaquant changeant d'IP à chaque requête passerait la première et
     * resterait arrêté par le second.</p>
     */
    private final LimiteurDebit demandesParNumero =
            new LimiteurDebit(DEMANDES_MAX, FENETRE);

    public ServiceConnexionWhatsApp(CodeConnexionRepository codes,
                                    IdentiteSocialeRepository identites,
                                    UtilisateurRepository utilisateurs,
                                    ClientRepository clients,
                                    ServiceAuthentification authentification,
                                    ServiceEvenementsSecurite securite,
                                    EnvoiWhatsApp envoi,
                                    PasswordEncoder encodeur,
                                    PlatformTransactionManager transactions) {
        this.codes = codes;
        this.identites = identites;
        this.utilisateurs = utilisateurs;
        this.clients = clients;
        this.authentification = authentification;
        this.securite = securite;
        this.envoi = envoi;
        this.encodeur = encodeur;
        this.transaction = new TransactionTemplate(transactions);

        // Voir l usage dans consommerEtRetrouver : ce qui doit survivre a
        // l annulation provoquee par CodeInvalide se commet ici.
        this.transactionIsolee = new TransactionTemplate(transactions);
        this.transactionIsolee.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Le service n'est pas configuré chez Meta. */
    public static class WhatsAppIndisponible extends ErreurMetier {

        public WhatsAppIndisponible() {
            super("WHATSAPP_INDISPONIBLE",
                    "La connexion par WhatsApp n'est pas disponible pour le moment.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /** Trop de demandes pour ce numéro. */
    public static class TropDeDemandes extends ErreurMetier {

        public TropDeDemandes() {
            super("TROP_DE_DEMANDES",
                    "Trop de codes demandés pour ce numéro. Réessayez dans quelques minutes.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
    }

    /**
     * Le code ne convient pas.
     *
     * <p>⚠️ <b>Un seul message pour toutes les causes</b> : code faux, expiré,
     * déjà servi, ou jamais demandé pour ce numéro. Les distinguer apprendrait
     * à un inconnu si un code est en cours sur un numéro donné — et donc si
     * quelqu'un est en train de se connecter.</p>
     */
    public static class CodeInvalide extends ErreurMetier {

        public CodeInvalide() {
            super("CODE_INVALIDE",
                    "Ce code ne convient pas, ou il a expiré. Demandez-en un nouveau.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNAUTHORIZED;
        }
    }

    // -------------------------------------------------------------------------
    // 1. Demander un code
    // -------------------------------------------------------------------------

    /**
     * Envoie un code sur WhatsApp.
     *
     * <p>🎯 <b>Ne dit JAMAIS si le numéro a déjà un compte</b>, et renvoie la
     * même chose dans les deux cas. Sans cette règle, le formulaire devient un
     * annuaire : on essaie des numéros jusqu'à voir changer la réponse, et on
     * sait qui est client chez GARAH.</p>
     *
     * @return le numéro normalisé et masqué, à afficher pour confirmation
     */
    public String demanderUnCode(String numeroSaisi) {
        if (!envoi.estActif()) {
            throw new WhatsAppIndisponible();
        }

        // La normalisation lève si le pays n'est pas desservi. C'est le SEUL
        // refus qui précède l'envoi, et il ne révèle rien : il porte sur la
        // forme du numéro, pas sur son existence chez nous.
        String telephone = NumeroTelephone.normaliser(numeroSaisi);

        if (demandesParNumero.tenter(telephone) < 0) {
            throw new TropDeDemandes();
        }

        String code = engendrerLeCode();

        transaction.executeWithoutResult(statut -> {
            // Le précédent meurt AVANT que le nouveau naisse : l'index unique
            // partiel l'exige, et la règle « un seul code vivant » aussi.
            codes.invalider(telephone);
            codes.flush();
            codes.save(new CodeConnexion(telephone, encodeur.encode(code)));
        });

        boolean parti = envoi.envoyerLeCode(telephone, code);

        if (!parti) {
            // ⚠️ On ne le dit PAS au client, et on ne lève pas.
            //
            //    « L'envoi a échoué » sur un numéro qui n'est pas sur WhatsApp
            //    apprend que ce numéro n'y est pas — une information sur
            //    quelqu'un d'autre. La personne verra simplement qu'aucun
            //    message n'arrive, ce qui est la même chose de son point de vue.
            log.warn("Code non parti vers {} : envoi WhatsApp en echec", telephone);
        }

        return NumeroTelephone.masquer(telephone);
    }

    /**
     * Six chiffres, tirés au sort de façon cryptographique.
     *
     * <p>Six et non quatre : quatre chiffres, c'est dix mille combinaisons, que
     * le plafond de cinq tentatives ne suffit pas à protéger si l'attaquant
     * peut redemander des codes. Six en fait un million.</p>
     */
    private static String engendrerLeCode() {
        return "%06d".formatted(HASARD.nextInt(1_000_000));
    }

    // -------------------------------------------------------------------------
    // 2. Vérifier le code, et ouvrir la session
    // -------------------------------------------------------------------------

    /** Ce que la transaction rend : le compte, et s'il vient de naître. */
    private record Aboutissement(Long utilisateurId, boolean creeMaintenant) { }

    public ResultatConnexion verifier(String numeroSaisi, String codeSaisi, String adresseIp) {
        String telephone = NumeroTelephone.normaliser(numeroSaisi);

        Aboutissement issue = transaction.execute(statut ->
                consommerEtRetrouver(telephone, codeSaisi, adresseIp));

        // ⚠️ LE JOURNAL S'ÉCRIT APRÈS LE COMMIT, jamais dedans.
        //
        //    ServiceEvenementsSecurite ouvre une transaction INDÉPENDANTE
        //    (REQUIRES_NEW), pour que la trace survive à une annulation
        //    (chapitre 18 §9.2). Conséquence : elle ne voit pas les lignes que
        //    la transaction en cours n'a pas encore commises.
        //
        //    Ma première version journalisait la création du compte à
        //    l'intérieur, et la base l'a refusée :
        //
        //        La clé (utilisateur_id)=(11) n'est pas présente
        //        dans la table « utilisateur »
        //
        //    La clé étrangère avait raison : au moment de l'écriture, ce compte
        //    n'existait pour personne d'autre que nous. C'est exactement pour
        //    cela que ServiceInscription écrit lui aussi ses traces après.
        if (issue.creeMaintenant()) {
            securite.enregistrer(issue.utilisateurId(), TypeEvenementSecurite.RATTACHEMENT_SOCIAL,
                    GraviteEvenement.INFO, adresseIp, "Compte cree par WhatsApp");
        }

        Utilisateur utilisateur = utilisateurs.findById(issue.utilisateurId()).orElseThrow();

        return authentification.ouvrirSession(utilisateur, adresseIp);
    }

    private Aboutissement consommerEtRetrouver(String telephone, String codeSaisi, String adresseIp) {
        CodeConnexion code = codes.findByTelephoneAndConsommeFalse(telephone)
                .filter(CodeConnexion::estUtilisable)
                .orElseThrow(CodeInvalide::new);

        if (!encodeur.matches(codeSaisi == null ? "" : codeSaisi.strip(), code.getCodeHache())) {

            // ⚠️ DANS UNE TRANSACTION INDÉPENDANTE, et c'est vital.
            //
            //    Ma première version incrémentait le compteur ici puis levait
            //    CodeInvalide. L'exception annulait la transaction — et
            //    EMPORTAIT l'incrément avec elle. Le compteur restait donc à
            //    zéro pour toujours, et le plafond de cinq tentatives ne
            //    s'appliquait JAMAIS.
            //
            //    Conséquence : six chiffres, un million de combinaisons,
            //    essayables sans limite. La protection existait dans le code,
            //    dans la table, dans le test — et ne protégeait rien.
            //
            //    C'est le test `plafondDeTentatives` qui l'a montré : le bon
            //    code ouvrait encore la session après cinq essais ratés.
            //
            //    Même mécanique que le journal de sécurité (ch. 18 §9.2) et que
            //    les statistiques (ch. 19 §8) : ce qui doit survivre à une
            //    annulation doit être commis ailleurs.
            transactionIsolee.executeWithoutResult(statut ->
                    codes.findByTelephoneAndConsommeFalse(telephone).ifPresent(vivant -> {
                        vivant.compterUnEchec();
                        codes.save(vivant);
                    }));

            securite.enregistrer(null, TypeEvenementSecurite.ECHEC_CONNEXION,
                    GraviteEvenement.FAIBLE, adresseIp,
                    "Code WhatsApp errone pour " + NumeroTelephone.masquer(telephone));

            throw new CodeInvalide();
        }

        // ⚠️ Consommé AVANT d'ouvrir la session, dans la même transaction.
        //    L'ordre inverse laisserait une fenêtre où deux requêtes
        //    simultanées présentant le même code ouvriraient deux sessions.
        code.consommer();
        codes.save(code);

        return retrouverOuCreer(telephone);
    }

    private Aboutissement retrouverOuCreer(String telephone) {
        Optional<IdentiteSociale> connue =
                identites.findByFournisseurAndSujet(FournisseurIdentite.WHATSAPP, telephone);

        if (connue.isPresent()) {
            IdentiteSociale lien = connue.get();
            lien.marquerUtilisee(null);
            return new Aboutissement(lien.getUtilisateur().getId(), false);
        }

        // ⚠️ AUCUN rattachement automatique à un compte existant portant ce
        //    numéro — contrairement à Google.
        //
        //    La différence est de fond : Google atteste une adresse qu'il a
        //    lui-même vérifiée. Ici, la colonne `utilisateur.telephone` a été
        //    SAISIE par quelqu'un, sans aucun contrôle, et rien ne garantit
        //    qu'elle appartienne au titulaire du compte. Rattacher sur cette
        //    base laisserait prendre le compte de quiconque a tapé son numéro
        //    par erreur — ou celui d'un autre exprès.
        //
        //    On crée donc un compte neuf. Le rattachement se fera depuis le
        //    profil, en étant déjà connecté, ce qui prouve les deux côtés.
        Utilisateur utilisateur = new Utilisateur(
                TypeUtilisateur.CLIENT, telephone, null, null);
        utilisateur.setTelephone(telephone);
        utilisateurs.save(utilisateur);

        clients.save(new Client(utilisateur, "CLI-%06d".formatted(clients.prochainCode())));

        identites.save(new IdentiteSociale(
                utilisateur, FournisseurIdentite.WHATSAPP, telephone, null));

        // La trace est écrite par l'appelant, APRÈS le commit. Voir verifier().
        return new Aboutissement(utilisateur.getId(), true);
    }

    // -------------------------------------------------------------------------

    /** Le bouton doit-il être proposé ? */
    public boolean estDisponible() {
        return envoi.estActif();
    }

    /** Efface les codes expirés. Appelée par le travail nocturne. */
    @Transactional
    public int purger() {
        return codes.purger(Instant.now());
    }
}
