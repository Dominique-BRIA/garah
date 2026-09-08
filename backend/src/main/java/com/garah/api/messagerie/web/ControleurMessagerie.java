package com.garah.api.messagerie.web;

import com.garah.api.messagerie.domaine.ServiceMessagerie;
import com.garah.api.messagerie.domaine.VueBlocage;
import com.garah.api.messagerie.domaine.VueCollegue;
import com.garah.api.messagerie.domaine.VueFilInterne;
import com.garah.api.messagerie.domaine.VueMessageInterne;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * La messagerie interne à l'équipe.
 *
 * <h2>⚠️ Deux contrôles différents, et ils ne se remplacent pas</h2>
 *
 * <ul>
 *   <li>une <b>autorité</b> — {@code MESSAGE_INTERNE_ECRIRE} — dit qui a le
 *       droit d'ouvrir la messagerie ;</li>
 *   <li>une <b>propriété</b> — le porteur du jeton — dit de quels fils il
 *       s'agit.</li>
 * </ul>
 *
 * <p>La première sans la seconde laisserait un agent lire les fils de tout le
 * monde ; la seconde sans la première ouvrirait la messagerie à des comptes qui
 * n'ont rien à y faire.</p>
 *
 * <h2>L'identifiant du jeton EST celui du responsable</h2>
 *
 * <p>{@code responsable} partage la clé primaire de {@code utilisateur}
 * ({@code @MapsId}). Le sujet du jeton sert donc directement — c'est déjà ce
 * que font les autres contrôleurs du back-office.</p>
 *
 * <h2>⚠️ Aucune entité ne traverse cette couche</h2>
 *
 * <p>Le service rend des <b>vues</b>. Exposer une entité, c'est publier son
 * schéma de base : un renommage de colonne casserait les trois frontends. Une
 * première version de ce contrôleur composait les vues lui-même à partir des
 * entités — le test d'architecture l'a refusée, et il avait raison.</p>
 */
@RestController
@RequestMapping("/api/messagerie")
@PreAuthorize("hasAuthority('MESSAGE_INTERNE_ECRIRE')")
public class ControleurMessagerie {

    private final ServiceMessagerie messagerie;

    public ControleurMessagerie(ServiceMessagerie messagerie) {
        this.messagerie = messagerie;
    }

    /** Mes fils, avec l'interlocuteur nommé et le nombre de non-lus. */
    @GetMapping("/fils")
    public List<VueFilInterne> mesFils(@AuthenticationPrincipal Jwt jeton) {
        return messagerie.mesFils(utilisateur(jeton));
    }

    /** La pastille de l'en-tête, présente sur chaque écran du back-office. */
    @GetMapping("/non-lus")
    public Map<String, Long> nonLus(@AuthenticationPrincipal Jwt jeton) {
        return Map.of("nombre", messagerie.totalNonLus(utilisateur(jeton)));
    }

    /**
     * Les collègues à qui l'on peut écrire.
     *
     * <p>⚠️ Ceux qui m'ont bloqué en sont retirés, et les inactifs aussi. Les
     * laisser dans la liste ferait choisir un destinataire pour se voir refuser
     * l'envoi juste après — un refus qu'on pouvait éviter avant le clic.</p>
     */
    @GetMapping("/joignables")
    public List<VueCollegue> joignables(@AuthenticationPrincipal Jwt jeton) {
        return messagerie.joignablesPar(utilisateur(jeton));
    }

    /** Le fil complet — marqué lu au passage. */
    @GetMapping("/fils/{id}")
    public List<VueMessageInterne> fil(@PathVariable Long id,
                                       @AuthenticationPrincipal Jwt jeton) {
        return messagerie.ouvrir(id, utilisateur(jeton));
    }

    /**
     * Écrire.
     *
     * <p>On désigne le <b>destinataire</b>, pas un fil : celui qui écrit pour la
     * première fois n'en a pas encore, et lui demander d'en créer un d'abord
     * serait un geste de plus pour rien.</p>
     */
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public VueMessageInterne envoyer(@Valid @RequestBody DemandeMessage demande,
                                     @AuthenticationPrincipal Jwt jeton) {
        return messagerie.envoyer(
                utilisateur(jeton), demande.destinataireId(), demande.contenu());
    }

    // -------------------------------------------------------------------------
    // Le blocage
    // -------------------------------------------------------------------------

    /**
     * Bloquer un collègue.
     *
     * <p>⚠️ Une autorité <b>supplémentaire</b> : écrire et bloquer ne sont pas
     * le même droit. « Supérieur » se traduit ici par « catégorie à qui l'Admin
     * a donné {@code MESSAGE_INTERNE_BLOQUER} » — il n'y a pas d'organigramme
     * dans ce schéma, et on n'en invente pas un.</p>
     *
     * <p>{@code PUT} : le geste est idempotent. Bloquer deux fois ne doit pas
     * échouer, sinon un double clic donne une erreur pour un état déjà
     * obtenu.</p>
     */
    @PutMapping("/blocages/{responsableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MESSAGE_INTERNE_BLOQUER')")
    public void bloquer(@PathVariable Long responsableId,
                        @RequestBody(required = false) DemandeBlocage demande,
                        @AuthenticationPrincipal Jwt jeton) {
        messagerie.bloquer(utilisateur(jeton), responsableId,
                demande == null ? null : demande.motif());
    }

    @DeleteMapping("/blocages/{responsableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MESSAGE_INTERNE_BLOQUER')")
    public void debloquer(@PathVariable Long responsableId,
                          @AuthenticationPrincipal Jwt jeton) {
        messagerie.debloquer(utilisateur(jeton), responsableId);
    }

    /** Ceux que J'AI bloqués — la liste de ses propres réglages. */
    @GetMapping("/blocages")
    public List<VueBlocage> mesBlocages(@AuthenticationPrincipal Jwt jeton) {
        return messagerie.mesBlocages(utilisateur(jeton));
    }

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    public record DemandeMessage(
            @NotNull(message = "Le destinataire est obligatoire.")
            Long destinataireId,

            @NotBlank(message = "Le message ne peut pas être vide.")
            @Size(max = 5000, message = "Le message est trop long.")
            String contenu) {
    }

    public record DemandeBlocage(
            @Size(max = 300, message = "Motif trop long.")
            String motif) {
    }
}
