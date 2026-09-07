package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.commun.erreur.RessourceIntrouvable;
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

    private final UtilisateurRepository utilisateurs;
    private final PasswordEncoder encodeur;
    private final ServiceRafraichissement sessions;
    private final ServiceEvenementsSecurite securite;

    public ServiceProfil(UtilisateurRepository utilisateurs,
                         PasswordEncoder encodeur,
                         ServiceRafraichissement sessions,
                         ServiceEvenementsSecurite securite) {
        this.utilisateurs = utilisateurs;
        this.encodeur = encodeur;
        this.sessions = sessions;
        this.securite = securite;
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
        return ProfilUtilisateur.de(charger(utilisateurId));
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

        return ProfilUtilisateur.de(utilisateur);
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
}
