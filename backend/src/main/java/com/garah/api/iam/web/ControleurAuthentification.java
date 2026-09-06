package com.garah.api.iam.web;

import com.garah.api.commun.web.AdresseClient;
import com.garah.api.iam.domaine.ServiceAuthentification;
import com.garah.api.iam.domaine.ServiceInscription;
import com.garah.api.iam.domaine.ServiceRafraichissement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Connexion, inscription, rafraîchissement, déconnexion.
 *
 * <h2>Le contrat en un coup d'œil</h2>
 *
 * <pre>
 * POST /api/auth/inscription   → 201 { accessToken, … } + cookie
 * POST /api/auth/connexion     → 200 { accessToken, … } + cookie
 * POST /api/auth/rafraichir    ← cookie seul            → 200 { accessToken } + NOUVEAU cookie
 * POST /api/auth/deconnexion   ← cookie seul            → 204 + cookie effacé
 * GET  /api/auth/moi           ← Bearer
 * </pre>
 *
 * <p><b>Le jeton d'accès n'est jamais mis en cookie</b>, et le jeton de
 * rafraîchissement n'est jamais renvoyé dans le JSON. Les mélanger annulerait
 * l'intérêt du découpage : un jeton de rafraîchissement lisible en JavaScript
 * redeviendrait volable par XSS.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class ControleurAuthentification {

    private final ServiceAuthentification authentification;
    private final ServiceInscription inscription;
    private final ServiceRafraichissement sessions;
    private final CookieRafraichissement cookie;

    public ControleurAuthentification(ServiceAuthentification authentification,
                                      ServiceInscription inscription,
                                      ServiceRafraichissement sessions,
                                      CookieRafraichissement cookie) {
        this.authentification = authentification;
        this.inscription = inscription;
        this.sessions = sessions;
        this.cookie = cookie;
    }

    /**
     * Créer un compte client. Route publique — c'est la porte d'entrée (D-07).
     *
     * <p>⚠️ <b>Cette route crée un compte sans aucune vérification d'identité.</b>
     * Deux protections manquent et doivent être posées avant l'ouverture au
     * public : une <b>limitation de débit</b> par adresse IP (chaque création
     * coûte 250 ms de BCrypt au serveur) et une <b>confirmation par
     * e-mail</b>.</p>
     */
    @PostMapping("/inscription")
    public ResponseEntity<ReponseConnexion> inscription(
            @Valid @RequestBody DemandeInscription demande,
            HttpServletRequest requete) {

        String ip = AdresseClient.de(requete);

        var connexion = inscription.inscrire(
                demande.email(), demande.motDePasse(), demande.nom(),
                demande.prenom(), demande.telephone(), demande.langue(), ip);

        return avecCookie(HttpStatus.CREATED, sessions.ouvrirSession(connexion, ip));
    }

    @PostMapping("/connexion")
    public ResponseEntity<ReponseConnexion> connexion(@Valid @RequestBody DemandeConnexion demande,
                                                      HttpServletRequest requete) {
        String ip = AdresseClient.de(requete);

        var connexion = authentification.connecter(demande.email(), demande.motDePasse(), ip);

        return avecCookie(HttpStatus.OK, sessions.ouvrirSession(connexion, ip));
    }

    /**
     * Échange le cookie contre un jeton d'accès neuf.
     *
     * <h2>Ce que cette route fait vraiment</h2>
     *
     * <p>Ce n'est pas une simple prolongation. À chaque appel, l'utilisateur et
     * ses permissions sont <b>relus en base</b> :</p>
     *
     * <ul>
     *   <li>un compte bloqué entre-temps est refusé, et toutes ses sessions
     *       sont coupées ;</li>
     *   <li>un droit retiré disparaît du nouveau jeton.</li>
     * </ul>
     *
     * <p>C'est ce qui ramène le délai de révocation de 60 minutes (D-16) à 15
     * au pire — et à zéro pour une déconnexion.</p>
     *
     * <p><b>Aucun corps de requête, aucun en-tête {@code Authorization}.</b> Le
     * cookie suffit, et c'est voulu : le frontend appelle cette route
     * justement quand son jeton d'accès a expiré.</p>
     */
    @PostMapping("/rafraichir")
    public ResponseEntity<ReponseConnexion> rafraichir(HttpServletRequest requete) {
        String presente = cookie.lire(requete).orElse(null);
        String ip = AdresseClient.de(requete);

        return avecCookie(HttpStatus.OK, sessions.rafraichir(presente, ip));
    }

    /**
     * Ferme la session.
     *
     * <p>Révoque la <b>famille entière</b> côté serveur et efface le cookie
     * côté navigateur. Les deux sont nécessaires : effacer le cookie sans
     * révoquer laisserait un jeton valide dans la nature — et c'est exactement
     * ce que fait une déconnexion « côté frontend seulement ».</p>
     *
     * <p>Répond {@code 204} même sans cookie valide : se déconnecter deux fois,
     * ou depuis un onglet oublié, est le cas <b>normal</b>. Le résultat voulu
     * est atteint dans tous les cas.</p>
     */
    @PostMapping("/deconnexion")
    public ResponseEntity<Void> deconnexion(HttpServletRequest requete) {
        cookie.lire(requete).ifPresent(sessions::fermerSession);

        return ResponseEntity.noContent()
                .header(CookieRafraichissement.enTete(), cookie.effacer())
                .build();
    }

    /**
     * Renvoie ce que le jeton porte. Utile aux frontends au démarrage, et
     * pratique pour comprendre ce qu'un JWT contient réellement.
     */
    @GetMapping("/moi")
    public Map<String, Object> moi(@AuthenticationPrincipal Jwt jeton) {
        return Map.of(
                "id", jeton.getSubject(),
                "nom", jeton.getClaimAsString("nom"),
                "type", jeton.getClaimAsString("type"),
                "langue", jeton.getClaimAsString("langue"),
                "permissions", jeton.getClaimAsStringList("permissions") == null
                        ? List.of() : jeton.getClaimAsStringList("permissions"),
                "expireLe", String.valueOf(jeton.getExpiresAt()));
    }

    /**
     * Assemble la réponse : le JSON pour l'accès, le cookie pour le refresh.
     *
     * <p>Un seul endroit fabrique cette paire. La dupliquer dans les quatre
     * routes garantirait qu'un jour l'une d'elles oublie le cookie — et le
     * symptôme serait une déconnexion au bout de 15 minutes, uniquement sur ce
     * chemin-là.</p>
     */
    private ResponseEntity<ReponseConnexion> avecCookie(HttpStatus statut,
                                                        ServiceRafraichissement.Couple couple) {
        return ResponseEntity.status(statut)
                .header(CookieRafraichissement.enTete(),
                        cookie.poser(couple.jetonRafraichissement(),
                                couple.dureeRafraichissementSecondes()))
                .body(ReponseConnexion.de(couple.connexion()));
    }
}
