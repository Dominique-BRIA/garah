package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.commun.stockage.DepotFichiers;
import com.garah.api.commun.stockage.StockageObjet;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.surveillance.domaine.GraviteEvenement;
import com.garah.api.surveillance.domaine.ServiceEvenementsSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Objects;

/**
 * Son propre compte : le consulter, le corriger, changer son mot de passe.
 *
 * <h2>Ce service ne connaît qu'un seul utilisateur : celui qui appelle</h2>
 *
 * <p>Chaque méthode prend l'identifiant tiré du <b>jeton</b>, jamais d'un
 * paramètre d'URL. C'est ce qui rend l'élévation de privilège impossible par
 * construction : il n'existe aucun chemin, dans ce fichier, permettant de
 * désigner le compte de quelqu'un d'autre.</p>
 *
 * <p>L'administration d'autrui — bloquer, changer un rôle, réinitialiser —
 * est un tout autre métier, avec ses propres permissions. Le mélanger ici
 * ferait de la moindre erreur de portée une faille.</p>
 */
@Service
public class ServiceProfil {

    private static final Logger log = LoggerFactory.getLogger(ServiceProfil.class);

    /**
     * 2 Mio pour une photo de profil.
     *
     * <p>Volontairement bien plus bas que pour un média de produit : une photo
     * de profil s'affiche dans un rond de 2,5 rem. Accepter 20 Mio ferait
     * payer à chaque visiteur, sur une connexion mobile camerounaise, le
     * téléchargement d'une image dont 99 % des pixels sont jetés à
     * l'affichage.</p>
     */
    private static final long TAILLE_MAX_PHOTO = 2L * 1024 * 1024;

    /** De quoi reconnaître une image à sa signature binaire. */
    private static final int OCTETS_SIGNATURE = 16;

    private final UtilisateurRepository utilisateurs;
    private final PasswordEncoder encodeur;
    private final ServiceRafraichissement sessions;
    private final ServiceEvenementsSecurite securite;
    private final DepotFichiers fichiers;
    private final StockageObjet stockage;

    public ServiceProfil(UtilisateurRepository utilisateurs,
                         PasswordEncoder encodeur,
                         ServiceRafraichissement sessions,
                         ServiceEvenementsSecurite securite,
                         DepotFichiers fichiers,
                         StockageObjet stockage) {
        this.utilisateurs = utilisateurs;
        this.encodeur = encodeur;
        this.sessions = sessions;
        this.securite = securite;
        this.fichiers = fichiers;
        this.stockage = stockage;
    }

    // -------------------------------------------------------------------------
    // Erreurs
    // -------------------------------------------------------------------------

    /**
     * Le mot de passe actuel ne correspond pas.
     *
     * <p>Message explicite, contrairement à l'écran de connexion où l'on dit
     * seulement « identifiants invalides ». La différence est justifiée :
     * ici l'appelant est <b>déjà authentifié</b> et prouve donc qu'il est le
     * propriétaire. Rester vague ne protégerait personne et laisserait quelqu'un
     * chercher longtemps lequel des deux champs il a mal saisi.</p>
     */
    public static class MotDePasseActuelIncorrect extends ErreurMetier {
        MotDePasseActuelIncorrect() {
            super("MOT_DE_PASSE_ACTUEL_INCORRECT",
                    "Le mot de passe actuel est incorrect.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /** Le nouveau mot de passe est identique à l'ancien. */
    public static class MotDePasseInchange extends ErreurMetier {
        MotDePasseInchange() {
            super("MOT_DE_PASSE_INCHANGE",
                    "Le nouveau mot de passe doit être différent de l'actuel.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ProfilUtilisateur lire(Long utilisateurId) {
        return vueDe(charger(utilisateurId));
    }

    // -------------------------------------------------------------------------
    // Modification
    // -------------------------------------------------------------------------

    /**
     * Corrige ses informations d'identité.
     *
     * <p>🎯 <b>L'adresse e-mail n'est pas modifiable ici, et c'est délibéré.</b>
     * Elle sert d'identifiant de connexion et de destination des liens de
     * confirmation (D-23). La changer suppose de vérifier qu'elle est libre, de
     * remettre {@code emailVerifie} à faux, d'envoyer un nouveau lien, et de
     * décider ce qui se passe si le propriétaire ne l'ouvre jamais — avec, au
     * bout, un compte dont l'adresse ne reçoit plus rien. C'est un parcours à
     * part entière, pas un champ de formulaire.</p>
     *
     * <p>Un champ absent de la demande n'est pas modifié : le formulaire de
     * profil peut n'envoyer que ce qui a changé. Un champ présent mais vide
     * <b>efface</b> la valeur, ce qui est le comportement voulu pour un
     * téléphone qu'on retire.</p>
     *
     * @param adresseIp pour le journal de sécurité, jamais pour autoriser
     */
    @Transactional
    public ProfilUtilisateur modifier(Long utilisateurId, String nom, String prenom,
                                      String telephone, String langue, String adresseIp) {

        Utilisateur utilisateur = charger(utilisateurId);
        String ancienTelephone = utilisateur.getTelephone();

        if (nom != null && !nom.isBlank()) {
            utilisateur.renommer(nom);
        }
        if (prenom != null) {
            utilisateur.setPrenom(prenom.isBlank() ? null : prenom.strip());
        }
        if (telephone != null) {
            utilisateur.setTelephone(telephone.isBlank() ? null : telephone.strip());
        }
        if (langue != null && !langue.isBlank()) {
            // La MÊME règle qu'à l'inscription : une langue inconnue retombe
            // sur le français plutôt que de faire échouer l'enregistrement.
            utilisateur.setLangue(ServiceInscription.langueValide(langue));
        }

        utilisateurs.save(utilisateur);

        // Le numéro de téléphone sert au paiement mobile money : son
        // changement se journalise, comme celui d'un mot de passe.
        if (!Objects.equals(ancienTelephone, utilisateur.getTelephone())) {
            securite.enregistrer(utilisateurId, TypeEvenementSecurite.CHANGEMENT_TELEPHONE,
                    GraviteEvenement.FAIBLE, adresseIp,
                    "Numéro de téléphone modifié depuis le profil.");
        }

        return vueDe(utilisateur);
    }

    // -------------------------------------------------------------------------
    // Mot de passe
    // -------------------------------------------------------------------------

    /**
     * Change son mot de passe, et <b>coupe toutes les sessions</b>.
     *
     * <h2>🎯 Pourquoi toutes, y compris celle qui appelle</h2>
     *
     * <p>Le cas d'usage principal d'un changement de mot de passe est le
     * soupçon de vol. Si l'on ne révoquait que les autres sessions, il
     * faudrait faire confiance à l'idée que la session courante est bien celle
     * du propriétaire — or c'est exactement ce dont on doute.</p>
     *
     * <p>Ne rien révoquer du tout serait pire : le voleur garde son jeton de
     * rafraîchissement <b>quatorze jours</b> (D-19), et le propriétaire croit
     * s'être protégé.</p>
     *
     * <p>Conséquence assumée : celui qui change son mot de passe est
     * déconnecté et doit se reconnecter. L'interface doit le <b>dire avant</b>,
     * sinon la déconnexion passe pour une panne.</p>
     *
     * <p>⚠️ Le jeton d'<b>accès</b>, lui, reste valide jusqu'à 15 minutes : un
     * JWT ne se révoque pas, c'est sa définition (D-19). C'est la raison pour
     * laquelle le frontend doit terminer la session <b>lui-même</b> après un
     * changement réussi, au lieu d'attendre le premier 401.</p>
     */
    @Transactional
    public void changerMotDePasse(Long utilisateurId, String actuel, String nouveau,
                                  String adresseIp) {

        Utilisateur utilisateur = charger(utilisateurId);

        if (actuel == null || !encodeur.matches(actuel, utilisateur.getMotDePasse())) {
            // Journalisé : quelqu'un qui tâtonne sur le mot de passe actuel
            // d'une session ouverte est un signal, pas une maladresse banale.
            securite.enregistrer(utilisateurId, TypeEvenementSecurite.CHANGEMENT_MOT_DE_PASSE,
                    GraviteEvenement.MOYENNE, adresseIp,
                    "Changement refusé : mot de passe actuel incorrect.");
            throw new MotDePasseActuelIncorrect();
        }

        if (nouveau == null || nouveau.length() < ServiceInscription.LONGUEUR_MOT_DE_PASSE_MIN) {
            throw new ServiceInscription.MotDePasseTropFaible();
        }

        if (encodeur.matches(nouveau, utilisateur.getMotDePasse())) {
            throw new MotDePasseInchange();
        }

        utilisateur.setMotDePasse(encodeur.encode(nouveau));
        utilisateurs.save(utilisateur);

        int coupees = sessions.revoquerToutesLesSessions(
                utilisateurId, JetonRafraichissement.Motif.MOT_DE_PASSE_CHANGE);

        securite.enregistrer(utilisateurId, TypeEvenementSecurite.CHANGEMENT_MOT_DE_PASSE,
                GraviteEvenement.MOYENNE, adresseIp,
                "Mot de passe modifié. Sessions coupées : " + coupees + ".");

        log.info("Mot de passe change pour l utilisateur {} : {} session(s) coupee(s).",
                utilisateurId, coupees);
    }

    // -------------------------------------------------------------------------

    private Utilisateur charger(Long utilisateurId) {
        return utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> RessourceIntrouvable.de("Utilisateur", utilisateurId));
    }

    /**
     * La vue, avec l'adresse de la photo <b>signée au moment de la lecture</b>.
     *
     * <p>Un seul endroit fabrique cette paire. La signature ne peut pas être
     * mise en cache côté base : elle expire (D-21), et une adresse rangée en
     * dur y serait morte au bout de sept jours.</p>
     */
    private ProfilUtilisateur vueDe(Utilisateur utilisateur) {
        String cle = utilisateur.getPhotoCle();
        return ProfilUtilisateur.de(utilisateur,
                cle == null || cle.isBlank() ? null : stockage.urlPublique(cle));
    }

    // -------------------------------------------------------------------------
    // La photo de profil
    // -------------------------------------------------------------------------

    /**
     * Remplace sa photo de profil.
     *
     * <h2>⚠️ Le type déclaré par le navigateur n'est pas cru</h2>
     *
     * <p>{@code Content-Type} est fourni par l'appelant : renommer
     * {@code virus.exe} en {@code photo.jpg} suffirait à le faire accepter. On
     * lit donc les <b>premiers octets</b> du fichier et on en déduit le type
     * réel ; s'il ne s'agit pas d'une image, on refuse.</p>
     *
     * <p>Les octets lus pour l'inspection sont remis en tête du flux. Sans
     * cela, le fichier déposé serait amputé de ses seize premiers octets —
     * donc corrompu, et silencieusement : le téléversement réussit, et c'est
     * l'affichage qui casse.</p>
     *
     * <p><b>Sur la transaction.</b> Le dépôt sur le stockage d'objets est fait
     * <b>hors</b> transaction : un appel réseau qui peut durer trente secondes
     * ne doit jamais retenir une connexion du pool PostgreSQL, réduit à 5 sur
     * Neon (D-14).</p>
     */
    public ProfilUtilisateur changerPhoto(Long utilisateurId, String typeDeclare,
                                          long taille, InputStream contenu) {

        if (taille > TAILLE_MAX_PHOTO) {
            throw new RegleMetierViolee("PHOTO_TROP_LOURDE",
                    "La photo ne doit pas dépasser 2 Mo.");
        }

        // Le compte doit exister AVANT le dépôt : sinon on paierait du stockage
        // pour un fichier aussitôt orphelin.
        Utilisateur utilisateur = charger(utilisateurId);
        String ancienne = utilisateur.getPhotoCle();

        byte[] debut;
        InputStream flux;
        try {
            debut = DepotFichiers.premiersOctets(contenu, OCTETS_SIGNATURE);
            flux = new SequenceInputStream(new ByteArrayInputStream(debut), contenu);
        } catch (IOException e) {
            throw new RegleMetierViolee("FICHIER_ILLISIBLE",
                    "Le fichier n'a pas pu être lu.");
        }

        String typeReel = DepotFichiers.typeReel(debut);
        if (typeReel == null || !typeReel.startsWith("image/")) {
            log.warn("Photo de profil refusee pour l utilisateur {} : contenu non "
                    + "reconnu comme image (type declare : {})", utilisateurId, typeDeclare);
            throw new RegleMetierViolee("TYPE_FICHIER_REFUSE",
                    "Ce fichier n'est pas une image reconnue. Formats admis : JPEG, PNG.");
        }

        // Appel réseau, HORS transaction.
        String nouvelleCle = fichiers.deposer("utilisateurs/" + utilisateurId,
                typeReel, taille, flux);

        enregistrerLaCle(utilisateurId, nouvelleCle);

        // L'ancienne n'est supprimée qu'APRÈS que la nouvelle soit enregistrée.
        // Dans l'ordre inverse, un échec d'écriture laisserait un compte dont
        // la photo a été détruite et non remplacée.
        supprimerSansEchouer(ancienne);

        return vueDe(charger(utilisateurId));
    }

    /** Revient à l'avatar engendré depuis le nom. */
    public ProfilUtilisateur retirerPhoto(Long utilisateurId) {
        Utilisateur utilisateur = charger(utilisateurId);
        String ancienne = utilisateur.getPhotoCle();

        enregistrerLaCle(utilisateurId, null);
        supprimerSansEchouer(ancienne);

        return vueDe(charger(utilisateurId));
    }

    /**
     * Écrit la clé, et rien d'autre.
     *
     * <p>⚠️ <b>Volontairement SANS {@code @Transactional}</b>, alors que le
     * réflexe serait d'en mettre un. L'annotation serait <b>inerte</b> :
     * {@code changerPhoto} appelle cette méthode sur {@code this}, et une
     * auto-invocation ne passe pas par le proxy Spring qui porte la
     * transaction. On croirait avoir une transaction courte ; on n'aurait
     * rien du tout, et rien ne le signalerait.</p>
     *
     * <p>Ce n'est pas un manque : {@code JpaRepository.save} est lui-même
     * transactionnel. L'écriture est donc bien atomique — simplement, c'est le
     * dépôt qui ouvre la transaction, pas cette méthode.</p>
     */
    private void enregistrerLaCle(Long utilisateurId, String cle) {
        Utilisateur utilisateur = charger(utilisateurId);
        utilisateur.setPhotoCle(cle);
        utilisateurs.save(utilisateur);
    }

    /**
     * ⚠️ Un fichier qu'on n'arrive pas à effacer ne doit pas faire échouer
     * l'opération.
     *
     * <p>La photo est déjà remplacée en base : lever ici rendrait un 500 à
     * quelqu'un dont le changement a parfaitement réussi. On laisse un objet
     * orphelin sur le stockage — quelques kilo-octets — et on le note.</p>
     */
    private void supprimerSansEchouer(String cle) {
        if (cle == null || cle.isBlank()) {
            return;
        }
        try {
            fichiers.supprimer(cle);
        } catch (RuntimeException e) {
            log.warn("Ancienne photo {} non supprimee du stockage : {}",
                    cle, e.getClass().getSimpleName());
        }
    }
}
