package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.iam.infra.CasUtilisationRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.iam.securite.ServiceJeton;
import com.garah.api.surveillance.domaine.GraviteEvenement;
import com.garah.api.surveillance.domaine.ServiceEvenementsSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * La connexion : vérifier une identité, puis fabriquer un jeton.
 */
@Service
public class ServiceAuthentification {

    /** Empreinte factice, utilisée quand le compte n'existe pas (voir §2). */
    private static final String EMPREINTE_LEURRE =
            "$2a$12$Yl3zvfM8xUqOl0lWq4mBOe5j6P3.pWpH0zGmHhKgqXqEYzGZ3d/8y";

    private final UtilisateurRepository utilisateurs;
    private final DroitsParType droitsDeduits;
    private final ServicePermissions permissions;
    private final ServiceJeton jetons;
    private final PasswordEncoder encodeur;
    private final ServiceEvenementsSecurite securite;

    public ServiceAuthentification(UtilisateurRepository utilisateurs,
                                   DroitsParType droitsDeduits,
                                   ServicePermissions permissions,
                                   ServiceJeton jetons,
                                   PasswordEncoder encodeur,
                                   ServiceEvenementsSecurite securite) {
        this.utilisateurs = utilisateurs;
        this.droitsDeduits = droitsDeduits;
        this.permissions = permissions;
        this.jetons = jetons;
        this.encodeur = encodeur;
        this.securite = securite;
    }

    /**
     * Erreur volontairement <b>identique</b> pour « compte inconnu » et
     * « mot de passe faux ».
     *
     * <p>Deux messages distincts transformeraient le formulaire de connexion
     * en <b>annuaire</b> : un attaquant essaierait des adresses jusqu'à voir
     * changer le message, et saurait alors qui possède un compte.</p>
     */
    public static class IdentifiantsInvalides extends ErreurMetier {
        IdentifiantsInvalides() {
            super("IDENTIFIANTS_INVALIDES", "Adresse e-mail ou mot de passe incorrect.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNAUTHORIZED;
        }
    }

    public static class CompteBloque extends ErreurMetier {
        CompteBloque() {
            super("COMPTE_BLOQUE", "Ce compte est désactivé. Contactez l'administration.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.FORBIDDEN;
        }
    }

    @Transactional
    public ResultatConnexion connecter(String email, String motDePasse, String adresseIp) {
        Optional<Utilisateur> trouve = utilisateurs.findByEmailIgnoreCase(email);

        // Compte inconnu : on vérifie quand même une empreinte factice.
        //
        // Sans ça, la réponse serait BEAUCOUP plus rapide pour un e-mail
        // inconnu (aucun BCrypt à calculer) que pour un e-mail existant
        // (~250 ms). Ce simple écart de temps suffit à savoir qui a un compte.
        // C'est une attaque par canal temporel, et elle est facile à mener.
        if (trouve.isEmpty()) {
            encodeur.matches(motDePasse, EMPREINTE_LEURRE);
            securite.enregistrerEchecConnexion(null, adresseIp, "Adresse inconnue : " + email);
            throw new IdentifiantsInvalides();
        }

        Utilisateur utilisateur = trouve.get();

        if (!encodeur.matches(motDePasse, utilisateur.getMotDePasse())) {
            securite.enregistrerEchecConnexion(utilisateur.getId(), adresseIp, "Mot de passe incorrect");
            throw new IdentifiantsInvalides();
        }

        // Le mot de passe est bon, mais le compte est fermé. On le dit
        // clairement : à ce stade la personne a PROUVÉ son identité, il n'y a
        // plus rien à protéger en restant vague — et un message flou la
        // ferait réessayer indéfiniment.
        if (!utilisateur.estActif()) {
            securite.enregistrer(utilisateur.getId(), TypeEvenementSecurite.BLOCAGE_COMPTE,
                    GraviteEvenement.MOYENNE, adresseIp, "Connexion refusée : compte " + utilisateur.getStatut());
            throw new CompteBloque();
        }

        Set<String> droits = permissionsDe(utilisateur);
        utilisateur.setDateDerniereConnexion(Instant.now());

        securite.enregistrer(utilisateur.getId(), TypeEvenementSecurite.CONNEXION_REUSSIE,
                GraviteEvenement.INFO, adresseIp, null);

        return new ResultatConnexion(
                jetons.creer(utilisateur, droits),
                jetons.dureeEnSecondes(),
                utilisateur.getId(),
                utilisateur.getType(),
                utilisateur.getNom(),
                utilisateur.getLangue(),
                utilisateur.getPhotoCle(),
                droits);
    }

    /**
     * Les droits selon le type d'acteur (chapitre 01 §3.2).
     *
     * <pre>
     * SUPER_ADMIN  tout, y compris le référentiel et la sécurité
     * ADMIN        tout SAUF le module SECURITE
     * RESPONSABLE  ce que ses catégories et ses exceptions lui donnent
     * CLIENT       aucune permission : son accès repose sur la PROPRIÉTÉ
     *              de ses données, pas sur des droits
     * </pre>
     *
     * <p>Publique parce que le rafraîchissement la rejoue à chaque
     * renouvellement (D-19) : les droits sont RELUS en base, jamais recopiés
     * du jeton précédent. C'est ce qui fait qu'un droit retiré s'applique en
     * 15 minutes au lieu de 60.</p>
     */
    public Set<String> permissionsDe(Utilisateur utilisateur) {
        TypeUtilisateur type = utilisateur.getType();

        // 🎯 La règle « quel type reçoit quoi » vit dans DroitsParType, et
        //    nulle part ailleurs. Elle est lue à DEUX endroits — ici, pour la
        //    réponse de connexion que l'écran consomme, et dans le
        //    convertisseur de jeton, pour Spring Security. Deux copies
        //    auraient fini par diverger, et « pourquoi cet administrateur
        //    voit-il un bouton que l'API lui refuse ? » ne se répond qu'en
        //    relisant les deux.
        if (droitsDeduits.seDeduisent(type)) {
            return droitsDeduits.pour(type);
        }

        return switch (type) {
            case RESPONSABLE -> permissions.permissionsEffectives(utilisateur.getId());
            case CLIENT -> Set.of();
            default -> throw new IllegalStateException("Type non traité : " + type);
        };
    }
}
