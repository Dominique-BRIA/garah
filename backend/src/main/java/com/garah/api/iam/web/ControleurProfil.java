package com.garah.api.iam.web;

import com.garah.api.commun.web.AdresseClient;
import com.garah.api.iam.domaine.ProfilUtilisateur;
import com.garah.api.iam.domaine.ServiceProfil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Son propre compte.
 *
 * <pre>
 * GET   /api/profil                 lire ses informations
 * PATCH /api/profil                 corriger nom, prénom, téléphone, langue
 * POST  /api/profil/mot-de-passe    changer son mot de passe
 * </pre>
 *
 * <h2>🎯 Aucune de ces routes ne prend d'identifiant</h2>
 *
 * <p>Le compte visé est <b>toujours</b> celui du jeton. Pas de
 * {@code /api/profil/{id}}, pas de {@code utilisateurId} dans le corps : il
 * n'existe aucun chemin, ici, permettant de désigner le compte d'autrui.
 * L'autorisation n'est donc pas une vérification qu'on pourrait oublier, elle
 * est une propriété de la forme des routes.</p>
 *
 * <p>C'est pourquoi ce contrôleur ne porte <b>aucun</b> {@code @PreAuthorize} :
 * il n'y a rien à autoriser au-delà d'être connecté, ce que la chaîne de
 * filtres impose déjà à tout ce qui n'est pas explicitement public. Un client
 * sans la moindre permission consulte et corrige son profil — c'est le
 * comportement voulu, son accès repose sur la propriété de ses données et non
 * sur des droits.</p>
 *
 * <h2>Pourquoi pas dans {@code ControleurAuthentification}</h2>
 *
 * <p>{@code /api/auth/*} traite l'<b>entrée et la sortie</b> : se connecter,
 * renouveler, se déconnecter, confirmer son adresse. Gérer son compte est un
 * autre sujet, et il grossira — préférences, appareils connectés, double
 * facteur. Le mettre là aurait produit un contrôleur de six cents lignes dont
 * la moitié des routes n'auraient plus rien à voir avec son nom.</p>
 *
 * <p>{@code GET /api/auth/moi} reste et ne fait pas doublon : il renvoie ce
 * que <b>le jeton</b> porte, sans toucher la base — utile aux frontends au
 * démarrage. {@code GET /api/profil} lit la <b>base</b>, et renvoie donc aussi
 * l'e-mail, le téléphone et les dates, qui ne sont pas dans le jeton.</p>
 */
@RestController
@RequestMapping("/api/profil")
public class ControleurProfil {

    private final ServiceProfil profils;

    public ControleurProfil(ServiceProfil profils) {
        this.profils = profils;
    }

    @GetMapping
    public ProfilUtilisateur lire(@AuthenticationPrincipal Jwt jeton) {
        return profils.lire(idDe(jeton));
    }

    /**
     * {@code PATCH} et non {@code PUT} : la demande décrit ce qui <b>change</b>,
     * pas l'état complet du compte. Un {@code PUT} obligerait le formulaire à
     * renvoyer tous les champs, et un champ oublié effacerait sa valeur.
     */
    @PatchMapping
    public ProfilUtilisateur modifier(@Valid @RequestBody DemandeModificationProfil demande,
                                      @AuthenticationPrincipal Jwt jeton,
                                      HttpServletRequest requete) {

        return profils.modifier(idDe(jeton), demande.nom(), demande.prenom(),
                demande.telephone(), demande.langue(), AdresseClient.de(requete));
    }

    /**
     * Change le mot de passe et <b>coupe toutes les sessions</b>, celle-ci
     * comprise.
     *
     * <p>⚠️ Répond {@code 204} et rien d'autre — surtout pas un nouveau jeton.
     * En délivrer un reviendrait à décider que la session courante est
     * légitime, alors que le cas d'usage principal d'un changement de mot de
     * passe est précisément le soupçon de vol.</p>
     *
     * <p>Le frontend doit donc terminer la session lui-même et rediriger vers
     * la connexion. Attendre le premier {@code 401} laisserait l'application
     * fonctionner encore quinze minutes sur le jeton d'accès en cours — un JWT
     * ne se révoque pas (D-19) — et la déconnexion tomberait plus tard, sans
     * rapport visible avec ce qu'on venait de faire.</p>
     */
    @PostMapping("/mot-de-passe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changerMotDePasse(@Valid @RequestBody DemandeChangementMotDePasse demande,
                                  @AuthenticationPrincipal Jwt jeton,
                                  HttpServletRequest requete) {

        profils.changerMotDePasse(idDe(jeton), demande.actuel(), demande.nouveau(),
                AdresseClient.de(requete));
    }

    /**
     * L'identifiant du compte appelant.
     *
     * <p>Le sujet du jeton est l'identifiant de l'utilisateur, écrit par
     * {@code ServiceJeton}. Il est signé : le modifier invaliderait la
     * signature, et la requête n'atteindrait jamais ce contrôleur.</p>
     */
    private Long idDe(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }
}
