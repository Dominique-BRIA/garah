package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.iam.infra.JetonRafraichissementRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.iam.securite.ServiceJeton;
import com.garah.api.surveillance.domaine.GraviteEvenement;
import com.garah.api.surveillance.domaine.ServiceEvenementsSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Le jeton de rafraîchissement : rendre la révocation possible (D-19).
 *
 * <h2>Le problème que ça résout</h2>
 *
 * <p>D-16 avait tranché : permissions dans le JWT, 60 minutes, aucun état
 * serveur. Le coût, assumé et écrit noir sur blanc : <b>un droit retiré met
 * jusqu'à une heure à s'appliquer, et un compte bloqué reste utilisable
 * jusqu'à l'expiration de son jeton</b>.</p>
 *
 * <p>Un JWT ne peut pas être annulé — c'est sa définition même : il se vérifie
 * sans rien consulter. La seule sortie est de le rendre <b>court</b>, et
 * d'adosser son renouvellement à quelque chose qui, lui, est révocable.</p>
 *
 * <pre>
 * accès              15 min   JWT, aucun état, jamais révocable
 * rafraîchissement   14 j     une ligne en base, révocable à tout instant
 * </pre>
 *
 * <p>À chaque rafraîchissement, on <b>relit l'utilisateur et ses permissions
 * en base</b>. C'est là que tout se joue : un compte bloqué ou un droit retiré
 * prend effet au prochain renouvellement, donc en 15 minutes au pire, au lieu
 * de 60 — et une déconnexion agit <b>immédiatement</b>.</p>
 *
 * <h2>Rotation et détection de vol</h2>
 *
 * <p>Un jeton de rafraîchissement ne sert qu'<b>une fois</b> : le présenter le
 * consomme et en produit un nouveau. Si un jeton déjà consommé revient, il
 * n'existe que deux explications, et aucune n'est bénigne :</p>
 *
 * <ul>
 *   <li>il a été volé, et le voleur s'en sert après la victime ;</li>
 *   <li>il a été volé, et la victime s'en sert après le voleur.</li>
 * </ul>
 *
 * <p>Impossible de savoir lequel des deux appelle. On révoque donc la
 * <b>famille entière</b> : les deux sont déconnectés, et le légitime se
 * reconnecte avec son mot de passe — que le voleur, lui, n'a pas.</p>
 *
 * <p>⚠️ Sans rotation, un jeton volé reste valable quatorze jours sans que
 * personne ne puisse le remarquer. C'est la rotation qui <b>rend le vol
 * visible</b> ; la révocation n'est que la réaction.</p>
 */
@Service
public class ServiceRafraichissement {

    private static final Logger log = LoggerFactory.getLogger(ServiceRafraichissement.class);

    /**
     * 32 octets tirés au sort — 256 bits.
     *
     * <p>Assez pour qu'aucune recherche exhaustive ne soit envisageable, ce qui
     * est la raison pour laquelle on peut se contenter de SHA-256 en base
     * plutôt que de BCrypt (voir {@link #empreinte}).</p>
     */
    private static final int OCTETS_JETON = 32;

    /**
     * {@link SecureRandom}, jamais {@link java.util.Random}.
     *
     * <p>{@code Random} est un générateur <b>prévisible</b> : à partir de
     * quelques valeurs observées, on retrouve son état interne et on prédit
     * toutes les suivantes. Ici cela reviendrait à fabriquer les jetons des
     * autres utilisateurs.</p>
     */
    private static final SecureRandom ALEA = new SecureRandom();

    private final JetonRafraichissementRepository jetons;
    private final UtilisateurRepository utilisateurs;
    private final ServiceAuthentification authentification;
    private final ServiceJeton accesJetons;
    private final ServiceEvenementsSecurite securite;
    private final Duration duree;

    /**
     * 🎯 <b>Les révocations défensives s'écrivent dans LEUR PROPRE transaction.</b>
     *
     * <p>Sans cela, elles n'existent pas. Le mécanisme, découvert par le test
     * et non par la relecture :</p>
     *
     * <pre>
     * rafraichir()                    ouvre T1
     *   détection d'une réutilisation
     *   revoquerFamille(...)          écrit dans T1
     *   throw RafraichissementRefuse  RuntimeException
     *                                 → T1 ANNULÉE
     *                                 → la révocation est effacée
     * </pre>
     *
     * <p>Le jeton volé restait donc parfaitement valide, et la détection de vol
     * n'était qu'un message dans les journaux. Le test
     * {@code laTraceDuVolEstPreservee} lisait {@code [null, ROTATION]} au lieu
     * de {@code REUTILISATION}.</p>
     *
     * <p>C'est exactement la raison pour laquelle
     * {@link ServiceEvenementsSecurite} écrit en {@code REQUIRES_NEW} depuis le
     * chapitre 08 : <b>une réaction doit survivre à ce qu'elle traite</b>. La
     * règle valait pour le journal ; elle vaut tout autant pour la mesure de
     * protection elle-même.</p>
     */
    private final TransactionTemplate transactionIsolee;

    public ServiceRafraichissement(JetonRafraichissementRepository jetons,
                                   UtilisateurRepository utilisateurs,
                                   ServiceAuthentification authentification,
                                   ServiceJeton accesJetons,
                                   ServiceEvenementsSecurite securite,
                                   PlatformTransactionManager transactions,
                                   @Value("${GARAH_REFRESH_EXPIRATION_JOURS:14}") long jours) {
        this.jetons = jetons;
        this.utilisateurs = utilisateurs;
        this.authentification = authentification;
        this.accesJetons = accesJetons;
        this.securite = securite;
        this.duree = Duration.ofDays(jours);

        this.transactionIsolee = new TransactionTemplate(transactions);
        this.transactionIsolee.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Le rafraîchissement a échoué : {@code 401}, et le frontend redirige vers
     * la connexion.
     *
     * <p>Le message est <b>identique</b> dans tous les cas — jeton inconnu,
     * expiré, révoqué, compte fermé. Distinguer « ce jeton n'existe pas » de
     * « ce jeton a été révoqué pour vol » renseignerait un attaquant sur
     * l'état de sa cible.</p>
     */
    public static class RafraichissementRefuse extends ErreurMetier {
        RafraichissementRefuse() {
            super("SESSION_EXPIREE", "Votre session a expiré. Veuillez vous reconnecter.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNAUTHORIZED;
        }
    }

    /** Un couple frais : le jeton d'accès, et le rafraîchissement EN CLAIR. */
    public record Couple(String jetonAcces, long dureeAccesSecondes,
                         String jetonRafraichissement, long dureeRafraichissementSecondes,
                         ResultatConnexion connexion) {
    }

    /**
     * Ouvre une session : émet le premier jeton de rafraîchissement.
     *
     * <p>Appelé après une authentification réussie. Une nouvelle
     * <b>famille</b> naît ici — les rotations successives la conserveront.</p>
     */
    @Transactional
    public Couple ouvrirSession(ResultatConnexion connexion, String adresseIp) {
        Utilisateur utilisateur = utilisateurs.findById(connexion.utilisateurId())
                .orElseThrow(RafraichissementRefuse::new);

        return emettre(utilisateur, UUID.randomUUID(), connexion, adresseIp);
    }

    /**
     * Échange un jeton de rafraîchissement contre un couple neuf.
     *
     * <p>C'est ici que la révocation devient réelle : l'utilisateur et ses
     * permissions sont <b>relus en base</b>, jamais repris du jeton précédent.
     * Un compte bloqué entre-temps est refusé, un droit retiré disparaît du
     * nouveau jeton d'accès.</p>
     *
     * @param presente le jeton en clair, tel qu'il arrive du cookie
     */
    @Transactional
    public Couple rafraichir(String presente, String adresseIp) {
        if (presente == null || presente.isBlank()) {
            throw new RafraichissementRefuse();
        }

        Optional<JetonRafraichissement> trouve = jetons.findByEmpreinte(empreinte(presente));

        if (trouve.isEmpty()) {
            // Jeton inconnu : cookie périmé après une purge, ou tâtonnement.
            // Rien à révoquer, rien à signaler — on refuse, simplement.
            throw new RafraichissementRefuse();
        }

        JetonRafraichissement jeton = trouve.get();

        // 🎯 LA DÉTECTION DE VOL.
        //
        // Le jeton existe mais n'est plus utilisable. S'il a été RÉVOQUÉ (et
        // non simplement expiré), c'est qu'il a déjà servi : quelqu'un
        // represénte un jeton consommé. On ne peut pas savoir si c'est la
        // victime ou le voleur, donc on coupe les deux.
        if (jeton.getDateRevocation() != null) {
            reagirAUneReutilisation(jeton, adresseIp);
            throw new RafraichissementRefuse();
        }

        if (!jeton.estUtilisable()) {
            // Simplement expiré : pas un vol, pas d'alerte.
            throw new RafraichissementRefuse();
        }

        Utilisateur utilisateur = utilisateurs.findById(jeton.getUtilisateurId())
                .orElseThrow(RafraichissementRefuse::new);

        // ⚠️ LE contrôle qui donne tout son sens à D-19.
        //
        // Sans lui, un compte bloqué continuerait de renouveler son accès
        // pendant quatorze jours. Le blocage ne serait qu'un affichage.
        if (!utilisateur.estActif()) {
            // ⚠️ REQUIRES_NEW : on va lever juste après, et le rollback
            // effacerait cette révocation. Un compte bloqué garderait alors
            // ses sessions ouvertes — exactement le défaut que D-19 corrige.
            transactionIsolee.executeWithoutResult(statut ->
                    jetons.revoquerPourUtilisateur(utilisateur.getId(),
                            JetonRafraichissement.Motif.COMPTE_FERME, Instant.now()));

            securite.enregistrer(utilisateur.getId(), TypeEvenementSecurite.BLOCAGE_COMPTE,
                    GraviteEvenement.MOYENNE, adresseIp,
                    "Rafraîchissement refusé : compte " + utilisateur.getStatut());

            throw new RafraichissementRefuse();
        }

        // Rotation : l'ancien est consommé, un nouveau prend sa place dans la
        // MÊME famille. C'est ce qui rendra visible une réutilisation future.
        jeton.revoquer(JetonRafraichissement.Motif.ROTATION);

        // Les permissions sont RELUES, jamais recopiées : c'est ce qui fait
        // qu'un droit retiré s'applique en 15 minutes au lieu de 60.
        Set<String> droits = authentification.permissionsDe(utilisateur);

        ResultatConnexion connexion = new ResultatConnexion(
                accesJetons.creer(utilisateur, droits),
                accesJetons.dureeEnSecondes(),
                utilisateur.getId(), utilisateur.getType(),
                utilisateur.getNom(), utilisateur.getLangue(),
                // Relue à chaque rafraîchissement, comme les permissions : une
                // photo changée sur un onglet apparaît sur les autres au
                // renouvellement suivant, sans reconnexion.
                utilisateur.getPhotoCle(), droits);

        return emettre(utilisateur, jeton.getFamille(), connexion, adresseIp);
    }

    /**
     * Ferme la session correspondant à ce jeton.
     *
     * <p>Révoque la <b>famille</b>, pas seulement le jeton présenté : une
     * déconnexion doit fermer la session, pas seulement son dernier maillon.</p>
     *
     * <p>Ne lève jamais. Se déconnecter avec un cookie déjà invalide est le cas
     * <b>normal</b> — session expirée, deuxième clic, onglet resté ouvert. Le
     * résultat voulu (« je ne suis plus connecté ») est atteint dans tous les
     * cas ; renvoyer une erreur ne ferait qu'inquiéter sans rien apporter.</p>
     */
    @Transactional
    public void fermerSession(String presente) {
        if (presente == null || presente.isBlank()) {
            return;
        }
        jetons.findByEmpreinte(empreinte(presente)).ifPresent(jeton ->
                jetons.revoquerFamille(jeton.getFamille(),
                        JetonRafraichissement.Motif.DECONNEXION, Instant.now()));
    }

    /**
     * Coupe toutes les sessions d'un utilisateur.
     *
     * <p>À appeler quand un compte est bloqué ou qu'un mot de passe change.
     * Sans cela, changer son mot de passe après un vol ne chasserait pas le
     * voleur — il garderait son jeton de rafraîchissement.</p>
     */
    @Transactional
    public int revoquerToutesLesSessions(Long utilisateurId,
                                         JetonRafraichissement.Motif motif) {
        return jetons.revoquerPourUtilisateur(utilisateurId, motif, Instant.now());
    }

    /**
     * Supprime les jetons expirés. Appelé par la tâche périodique.
     *
     * <p>On garde sept jours après l'expiration : les lignes récentes servent à
     * comprendre un incident (« ce jeton a-t-il été réutilisé ? »).</p>
     */
    @Transactional
    public int purger() {
        return jetons.purger(Instant.now().minus(Duration.ofDays(7)));
    }

    // -------------------------------------------------------------------------

    /**
     * Un jeton déjà consommé revient : on coupe la famille entière.
     *
     * <p>Révoquer le seul jeton présenté punirait la victime et laisserait
     * courir le voleur — qui détient, lui, le jeton issu de la rotation.</p>
     */
    private void reagirAUneReutilisation(JetonRafraichissement jeton, String adresseIp) {
        // ⚠️ REQUIRES_NEW, et c'est ce qui fait toute la différence entre une
        // protection réelle et un message dans les journaux.
        //
        // L'appelant lève RafraichissementRefuse immédiatement après. Dans la
        // transaction courante, ce rollback effacerait la révocation : le
        // jeton volé resterait valide, et la seule trace du vol serait une
        // ligne de log que personne ne lit.
        Integer coupes = transactionIsolee.execute(statut ->
                jetons.revoquerFamille(jeton.getFamille(),
                        JetonRafraichissement.Motif.REUTILISATION, Instant.now()));

        log.warn("Reutilisation d'un jeton de rafraichissement (utilisateur {}, famille {}) : "
                + "{} jeton(s) revoque(s). Vol presume.",
                jeton.getUtilisateurId(), jeton.getFamille(), coupes);

        securite.enregistrer(jeton.getUtilisateurId(),
                TypeEvenementSecurite.ACTIVITE_INHABITUELLE, GraviteEvenement.HAUTE, adresseIp,
                "Jeton de rafraîchissement réutilisé : toutes les sessions ont été fermées.");
    }

    /** Crée et enregistre un jeton, et renvoie sa valeur EN CLAIR. */
    private Couple emettre(Utilisateur utilisateur, UUID famille,
                           ResultatConnexion connexion, String adresseIp) {

        byte[] brut = new byte[OCTETS_JETON];
        ALEA.nextBytes(brut);

        // Base64 URL-safe sans remplissage : transportable tel quel dans un
        // cookie, sans échappement.
        String enClair = Base64.getUrlEncoder().withoutPadding().encodeToString(brut);

        jetons.save(new JetonRafraichissement(
                utilisateur.getId(), empreinte(enClair), famille,
                Instant.now().plus(duree), adresseIp));

        return new Couple(connexion.jeton(), connexion.dureeSecondes(),
                enClair, duree.toSeconds(), connexion);
    }

    /**
     * SHA-256, en hexadécimal.
     *
     * <p>⚠️ <b>Pas BCrypt</b>, et c'est délibéré malgré l'habitude prise au
     * chapitre 08. BCrypt est lent <i>exprès</i>, parce qu'un mot de passe
     * humain a peu d'entropie et doit résister à un dictionnaire. Ce jeton est
     * 256 bits tirés au sort : aucun dictionnaire n'existe, et le forcer est
     * déjà hors d'atteinte. Le ralentir ne protégerait rien et coûterait
     * 250 ms à <b>chaque</b> rafraîchissement, toutes les 15 minutes, pour
     * chaque utilisateur connecté.</p>
     *
     * <p>La règle : BCrypt pour ce qu'un humain a choisi, hachage rapide pour
     * ce que la machine a tiré au sort.</p>
     */
    static String empreinte(String enClair) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(enClair.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est exigé par la spécification de la plateforme Java :
            // cette branche est inatteignable.
            throw new IllegalStateException("SHA-256 introuvable", e);
        }
    }
}
