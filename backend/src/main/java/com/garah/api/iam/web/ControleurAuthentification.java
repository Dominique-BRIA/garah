package com.garah.api.iam.web;

import com.garah.api.commun.web.AdresseClient;
import com.garah.api.iam.domaine.ServiceAuthentification;
import com.garah.api.iam.domaine.ServiceInscription;
import com.garah.api.iam.domaine.ServiceRafraichissement;
import com.garah.api.iam.domaine.ServiceVerificationEmail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final ServiceVerificationEmail verification;

    public ControleurAuthentification(ServiceAuthentification authentification,
                                      ServiceInscription inscription,
                                      ServiceRafraichissement sessions,
                                      CookieRafraichissement cookie,
                                      ServiceVerificationEmail verification) {
        this.authentification = authentification;
        this.inscription = inscription;
        this.sessions = sessions;
        this.cookie = cookie;
        this.verification = verification;
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

    // -------------------------------------------------------------------------
    // Confirmation de l'adresse e-mail (D-23)
    // -------------------------------------------------------------------------

    /**
     * Le lien reçu par e-mail. <b>Route publique — elle doit l'être.</b>
     *
     * <p>Celui qui clique n'est pas forcément connecté : il vient d'ouvrir sa
     * boîte mail, souvent sur un autre appareil. Exiger un jeton ici rendrait
     * le lien inutilisable dans le cas le plus courant.</p>
     *
     * <p><b>Ce qui protège cette route n'est donc pas l'authentification, mais
     * le jeton lui-même</b> : 256 bits tirés au sort, à usage unique, valable
     * 48 heures, et lié à l'adresse visée au moment de l'émission.</p>
     *
     * <p>Renvoie une page HTML minimale plutôt que du JSON — seule entorse au
     * reste de l'API, et elle est assumée : un humain lit cette réponse dans
     * son navigateur. Tant qu'Angular n'existe pas, c'est ce qui rend le flux
     * complet ; ensuite, {@code GARAH_URL_VERIFICATION} fera pointer le lien
     * vers le frontend et cette route deviendra un repli.</p>
     */
    @GetMapping(value = "/verification", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmerParLien(@RequestParam String jeton) {
        try {
            verification.confirmer(jeton);
            return ResponseEntity.ok(page("Adresse confirmée",
                    "Votre adresse est confirmée. Vous pouvez maintenant commander sur GARAH."));
        } catch (ServiceVerificationEmail.LienInvalide e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(page("Lien expiré", e.getMessage()));
        }
    }

    /** La même confirmation, en JSON — c'est celle que le frontend appellera. */
    @PostMapping("/verification")
    public Map<String, Object> confirmer(@Valid @RequestBody DemandeVerification demande) {
        verification.confirmer(demande.jeton());
        return Map.of("confirme", true);
    }

    /**
     * Renvoyer un lien. Réservé au titulaire du compte, qui doit être connecté.
     *
     * <p>Un client non confirmé <b>peut</b> se connecter — c'est justement ce
     * qui lui permet de demander ce renvoi. Ouvrir cette route aux anonymes en
     * ferait un outil d'envoi d'e-mails vers n'importe quelle adresse, à notre
     * nom et à nos frais.</p>
     *
     * <p>Répond {@code 204} même si l'adresse est déjà confirmée : redemander
     * un lien dont on n'avait pas besoin n'est pas une faute.</p>
     */
    @PostMapping("/verification/renvoi")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void renvoyer(@AuthenticationPrincipal Jwt jeton) {
        verification.renvoyer(Long.valueOf(jeton.getSubject()));
    }

    /**
     * Une page sobre, sans ressource distante.
     *
     * <p>Écrite à la main plutôt qu'avec un moteur de gabarits : ajouter
     * Thymeleaf pour deux pages ferait entrer toute une couche de rendu dans
     * une API qui n'en a aucun autre usage.</p>
     */
    private static String page(String titre, String message) {
        return """
               <!doctype html><html lang="fr"><head><meta charset="utf-8">
               <meta name="viewport" content="width=device-width,initial-scale=1">
               <title>%s — GARAH</title>
               <style>
                 body{font-family:system-ui,sans-serif;max-width:34rem;margin:4rem auto;
                      padding:0 1.5rem;line-height:1.6;color:#1f2937}
                 h1{font-size:1.5rem;margin-bottom:.5rem}
                 p{color:#4b5563}
               </style></head>
               <body><h1>%s</h1><p>%s</p></body></html>
               """.formatted(titre, titre, message);
    }

    /** Ce que le frontend envoie pour confirmer. */
    public record DemandeVerification(
            @jakarta.validation.constraints.NotBlank(message = "Le jeton est obligatoire.")
            @jakarta.validation.constraints.Size(max = 200, message = "Jeton trop long.")
            String jeton) {
    }

}
