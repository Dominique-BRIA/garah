package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.IdentiteSocialeRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.surveillance.domaine.GraviteEvenement;
import com.garah.api.surveillance.domaine.ServiceEvenementsSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * « Continuer avec Google » — et, plus tard, avec Facebook ou TikTok.
 *
 * <h2>Les trois cas, dans cet ordre</h2>
 *
 * <pre>
 * 1. IDENTITE CONNUE      le sujet est deja rattache → on ouvre la session
 * 2. ADRESSE CONNUE       un compte existe avec cette adresse
 *                         → on RATTACHE, si le fournisseur atteste l adresse
 * 3. PERSONNE INCONNUE    → on cree le compte, deja verifie
 * </pre>
 *
 * <p>L'ordre n'est pas indifférent : chercher d'abord par l'adresse ferait
 * dépendre l'identification d'une donnée qui change.</p>
 *
 * <h2>Le rattachement, et ce qui le rend sûr</h2>
 *
 * <p>Le cas 2 est le seul qui mérite un débat. Quelqu'un s'est inscrit avec un
 * mot de passe il y a six mois, revient, et clique « Continuer avec Google »
 * avec la même adresse. On rattache — <b>à une condition</b> :</p>
 *
 * <pre>
 * identite.emailVerifie() == true
 * </pre>
 *
 * <p>⚠️ <b>C'est ce drapeau, et lui seul, qui rend l'opération sûre.</b> Il ne
 * vient pas du client : il est lu dans un jeton signé par Google, qui atteste
 * ainsi avoir lui-même vérifié que cette personne contrôle cette boîte. Sans
 * ce contrôle, n'importe qui créerait un compte chez un fournisseur laxiste en
 * déclarant l'adresse d'un client GARAH, et prendrait son compte.</p>
 *
 * <p>C'est la raison pour laquelle {@link FournisseurIdentite} porte
 * {@code fournitUnEmailVerifie} : Facebook et TikTok ne pourront jamais
 * rattacher un compte existant tout seuls.</p>
 *
 * <p>Le rattachement est journalisé comme un <b>événement de sécurité</b>. Un
 * compte qui change de moyen d'accès est exactement ce qu'on veut pouvoir
 * relire après une réclamation.</p>
 */
@Service
public class ServiceConnexionSociale {

    private final VerificateurIdentiteSociale verificateur;
    private final IdentiteSocialeRepository identites;
    private final UtilisateurRepository utilisateurs;
    private final ClientRepository clients;
    private final ServiceAuthentification authentification;
    private final ServiceEvenementsSecurite securite;
    private final TransactionTemplate transaction;

    public ServiceConnexionSociale(VerificateurIdentiteSociale verificateur,
                                   IdentiteSocialeRepository identites,
                                   UtilisateurRepository utilisateurs,
                                   ClientRepository clients,
                                   ServiceAuthentification authentification,
                                   ServiceEvenementsSecurite securite,
                                   PlatformTransactionManager transactions) {
        this.verificateur = verificateur;
        this.identites = identites;
        this.utilisateurs = utilisateurs;
        this.clients = clients;
        this.authentification = authentification;
        this.securite = securite;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** L'adresse est déjà prise, et le fournisseur ne l'atteste pas. */
    public static class RattachementRefuse extends ErreurMetier {

        public RattachementRefuse() {
            super("RATTACHEMENT_REFUSE",
                    "Un compte existe déjà avec cette adresse. Connectez-vous avec "
                            + "votre mot de passe, puis liez ce service depuis votre profil.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.CONFLICT;
        }
    }

    public ResultatConnexion connecter(FournisseurIdentite fournisseur, String jeton,
                                       String adresseIp) {

        // 1. Vérifier AVANT toute écriture. Rien de ce qui suit ne doit
        //    dépendre d'une donnée envoyée par le client (D-17).
        IdentiteVerifiee identite = verificateur.verifier(fournisseur, jeton);

        Long utilisateurId = transaction.execute(statut -> retrouverOuCreer(identite, adresseIp));

        // Relu hors de la transaction d'écriture, comme le fait l'inscription :
        // ouvrirSession journalise et doit voir un compte réellement commis.
        Utilisateur utilisateur = utilisateurs.findById(utilisateurId).orElseThrow();

        return authentification.ouvrirSession(utilisateur, adresseIp);
    }

    private Long retrouverOuCreer(IdentiteVerifiee identite, String adresseIp) {

        // --- Cas 1 : identité déjà connue -----------------------------------
        Optional<IdentiteSociale> connue =
                identites.findByFournisseurAndSujet(identite.fournisseur(), identite.sujet());

        if (connue.isPresent()) {
            IdentiteSociale lien = connue.get();
            lien.marquerUtilisee(identite.email());
            return lien.getUtilisateur().getId();
        }

        String adresse = identite.email() == null ? "" : identite.email().strip();

        // ⚠️ D-53 : une identité SANS adresse ouvre quand même un compte.
        //
        //    Auparavant on levait AdresseIndisponible, ce qui rendait TikTok
        //    inutilisable — il n'en fournit jamais — et Facebook aléatoire.
        //    Le compte naît désormais sans adresse, exactement comme un compte
        //    WhatsApp (V39).
        //
        //    🎯 Le prix, et il est réel : ce compte n'a AUCUN canal hors de
        //    l'application. Si son colis arrive à Bangui et qu'il n'ouvre pas
        //    l'application, personne ne peut le prévenir. C'est le risque que
        //    D-23 fermait, rouvert en connaissance de cause.
        if (adresse.isBlank()) {
            return creerLeCompte(identite, null);
        }

        // --- Cas 2 : l'adresse appartient déjà à quelqu'un -------------------
        Optional<Utilisateur> existant = utilisateurs.findByEmailIgnoreCase(adresse);

        if (existant.isPresent()) {
            Utilisateur utilisateur = existant.get();

            // ⚠️ LE contrôle. Voir la javadoc de la classe.
            if (!identite.emailVerifie() || !identite.fournisseur().fournitUnEmailVerifie()) {
                securite.enregistrer(utilisateur.getId(),
                        TypeEvenementSecurite.ACTIVITE_INHABITUELLE, GraviteEvenement.HAUTE,
                        adresseIp, "Rattachement " + identite.fournisseur()
                                + " refuse : adresse non attestee par le fournisseur");
                throw new RattachementRefuse();
            }

            identites.save(new IdentiteSociale(
                    utilisateur, identite.fournisseur(), identite.sujet(), adresse));

            securite.enregistrer(utilisateur.getId(),
                    TypeEvenementSecurite.RATTACHEMENT_SOCIAL, GraviteEvenement.MOYENNE,
                    adresseIp, "Compte rattache a " + identite.fournisseur()
                            + " (adresse attestee par le fournisseur)");

            return utilisateur.getId();
        }

        // --- Cas 3 : personne inconnue --------------------------------------
        return creerLeCompte(identite, adresse);
    }

    /**
     * Crée le compte, avec ou sans adresse.
     *
     * @param adresse l'adresse annoncée, ou {@code null} si le fournisseur
     *                n'en donne aucune (TikTok toujours, Facebook parfois)
     */
    private Long creerLeCompte(IdentiteVerifiee identite, String adresse) {
        Utilisateur utilisateur = new Utilisateur(
                TypeUtilisateur.CLIENT, nomOuDefaut(identite, adresse), adresse, null);

        // 🎯 Marqué vérifié UNIQUEMENT si le fournisseur l'atteste.
        //
        //    Pour Google, c'est D-23 honoré sans envoyer le moindre courriel :
        //    lui demander de cliquer un lien reviendrait à redemander ce qu'on
        //    vient de recevoir signé.
        //
        //    ⚠️ Pour Facebook, surtout pas : Meta ne dit pas s'il a vérifié
        //    l'adresse. La marquer vérifiée en ferait une preuve qu'elle n'est
        //    pas — et permettrait plus tard d'y envoyer un code de retrait sans
        //    que personne n'ait jamais confirmé la contrôler.
        if (adresse != null && identite.emailVerifie()
                && identite.fournisseur().fournitUnEmailVerifie()) {
            utilisateur.marquerEmailVerifie();
        }

        utilisateurs.save(utilisateur);

        // Les deux naissent ensemble, comme à l'inscription : un utilisateur
        // sans ligne client se connecte puis échoue au premier ajout au
        // panier — et le défaut ne se voit qu'au moment de payer.
        clients.save(new Client(utilisateur, "CLI-%06d".formatted(clients.prochainCode())));

        identites.save(new IdentiteSociale(
                utilisateur, identite.fournisseur(), identite.sujet(), adresse));

        return utilisateur.getId();
    }

    /**
     * Le nom affiché, ou la partie gauche de l'adresse.
     *
     * <p>{@code utilisateur.nom} est {@code NOT NULL}, et tous les
     * fournisseurs ne donnent pas de nom. Un repli discret vaut mieux qu'un
     * échec d'inscription pour un champ d'affichage.</p>
     */
    private static String nomOuDefaut(IdentiteVerifiee identite, String adresse) {
        if (identite.nom() != null && !identite.nom().isBlank()) {
            return identite.nom().strip();
        }

        // ⚠️ `adresse` peut être null depuis D-53 : TikTok n'en donne aucune,
        //    et n'impose pas non plus de nom d'affichage. Sans ce garde, la
        //    création levait une NullPointerException — qui n'est pas une
        //    ErreurMetier, donc un 500 au lieu d'un compte.
        if (adresse != null && !adresse.isBlank()) {
            int arobase = adresse.indexOf('@');
            return arobase < 0 ? adresse : adresse.substring(0, arobase);
        }

        // Dernier repli : `utilisateur.nom` est NOT NULL, et il faut bien
        // afficher quelque chose. La personne le changera depuis son profil.
        return "Client " + identite.fournisseur();
    }
}
