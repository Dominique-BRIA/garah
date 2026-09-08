package com.garah.api.notification.web;

import com.garah.api.notification.domaine.ServiceAppareils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * L'abonnement d'un appareil aux notifications.
 *
 * <h2>⚠️ Aucun {@code @PreAuthorize}, et ce n'est pas un oubli</h2>
 *
 * <p>Le contrôle n'est pas une <b>autorité</b> mais une <b>propriété</b> : on
 * abonne l'appareil au porteur du jeton, jamais à un identifiant reçu du
 * client. Accepter un {@code utilisateurId} dans le corps reviendrait à laisser
 * n'importe qui recevoir les notifications de n'importe qui — y compris
 * « votre marchandise vous attend », qui désigne un colis et un code.</p>
 */
@RestController
@RequestMapping("/api/notifications")
public class ControleurNotification {

    private final ServiceAppareils appareils;

    public ControleurNotification(ServiceAppareils appareils) {
        this.appareils = appareils;
    }

    /**
     * Déclare cet appareil.
     *
     * <p>{@code PUT} et non {@code POST} : l'application redéclare son jeton à
     * chaque démarrage et à chaque renouvellement par Google. Le geste est
     * <b>idempotent</b>, et le verbe doit le dire.</p>
     */
    @PutMapping("/appareils")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void declarer(@Valid @RequestBody DemandeAppareil demande,
                         @AuthenticationPrincipal Jwt jeton) {
        appareils.declarer(demande.jeton(), utilisateur(jeton), demande.plateforme());
    }

    /**
     * Retire cet appareil — à la déconnexion.
     *
     * <p>Le service vérifie que le jeton est bien celui de l'appelant : sans
     * ce contrôle, il suffirait de deviner un jeton pour désabonner le
     * téléphone de quelqu'un d'autre.</p>
     */
    @DeleteMapping("/appareils/{jetonAppareil}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirer(@PathVariable String jetonAppareil,
                        @AuthenticationPrincipal Jwt jeton) {
        appareils.retirer(jetonAppareil, utilisateur(jeton));
    }

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    public record DemandeAppareil(
            @NotBlank(message = "Le jeton de l'appareil est obligatoire.")
            @Size(max = 500, message = "Jeton trop long.")
            String jeton,

            /*
             * Contraint ICI et par un CHECK en base : une plateforme inconnue
             * passerait sinon jusqu'à l'envoi, où l'erreur ne dirait plus d'où
             * elle vient.
             */
            @Pattern(regexp = "ANDROID|IOS|WEB",
                     message = "Plateforme inconnue.")
            String plateforme) {
    }
}
