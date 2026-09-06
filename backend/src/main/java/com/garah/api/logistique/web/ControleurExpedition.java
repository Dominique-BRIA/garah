package com.garah.api.logistique.web;

import com.garah.api.logistique.domaine.ServiceExpedition;
import com.garah.api.logistique.domaine.TypeEvenement;
import com.garah.api.logistique.domaine.VueEvenement;
import com.garah.api.logistique.domaine.VueExpedition;
import com.garah.api.logistique.domaine.VueLigneColis;
import com.garah.api.logistique.domaine.VueRetrait;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * L'acheminement : expéditions, colis, événements de parcours et retraits.
 *
 * <p>GARAH ne livre pas à domicile (D-05). La marchandise voyage de point de
 * transit en point de transit jusqu'au point de récupération choisi à la
 * commande, où le client vient la retirer avec un code.</p>
 */
@RestController
@RequestMapping("/api/expeditions")
public class ControleurExpedition {

    private final ServiceExpedition expeditions;

    public ControleurExpedition(ServiceExpedition expeditions) {
        this.expeditions = expeditions;
    }

    // -------------------------------------------------------------------------
    // Préparer et expédier
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EXPEDITION_CREER')")
    public VueExpedition creer(@Valid @RequestBody DemandeExpedition demande) {
        return VueExpedition.resume(expeditions.creer(
                demande.commandeId(), demande.lieuDepartId(),
                demande.pointRecuperationId(), demande.itineraireId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPEDITION_CONSULTER')")
    public VueExpedition detail(@PathVariable Long id) {
        return expeditions.vue(id);
    }

    @PostMapping("/{id}/colis")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EXPEDITION_PREPARER')")
    public VueExpedition.VueColis ajouterColis(@PathVariable Long id,
                                               @Valid @RequestBody DemandeColis demande) {
        return VueExpedition.VueColis.de(
                expeditions.ajouterColis(id, demande.numeroSuivi()));
    }

    /**
     * Place une ligne de commande dans un colis.
     *
     * <p>Le service vérifie qu'on ne met pas dans les colis plus que ce qui a
     * été commandé. Sans ce contrôle, la « projection » (ce que les colis
     * contiennent) et la commande divergeraient — et personne ne s'en
     * apercevrait avant l'arrivée à Bangui.</p>
     */
    @PostMapping("/colis/{colisId}/lignes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EXPEDITION_PREPARER')")
    public VueLigneColis ligne(@PathVariable Long colisId,
                               @Valid @RequestBody DemandeLigneColis demande) {
        // La conversion en DTO a lieu dans le service, pas ici : lire un getter
        // de LigneColis depuis la couche web violerait la règle d'architecture
        // du chapitre 06 — et ArchUnit l'a effectivement refusé.
        return expeditions.remplirEtResumer(colisId,
                demande.ligneCommandeId(), demande.quantite());
    }

    // -------------------------------------------------------------------------
    // Le parcours
    // -------------------------------------------------------------------------

    /**
     * Enregistre une étape du parcours d'un colis.
     *
     * <p>C'est l'opération la plus fréquente de tout le domaine logistique :
     * chaque scan à chaque point de transit passe par ici.</p>
     */
    @PostMapping("/colis/{colisId}/evenements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TRANSIT_ENREGISTRER_DEPART')")
    public VueEvenement enregistrer(@PathVariable Long colisId,
                                    @Valid @RequestBody DemandeEvenement demande,
                                    @AuthenticationPrincipal Jwt jeton) {
        return VueEvenement.de(expeditions.enregistrer(
                colisId, demande.lieuId(), utilisateur(jeton),
                demande.type(), demande.observation()));
    }

    @GetMapping("/colis/{colisId}/parcours")
    @PreAuthorize("hasAuthority('EXPEDITION_CONSULTER_HISTORIQUE')")
    public List<VueEvenement> parcours(@PathVariable Long colisId) {
        return expeditions.parcours(colisId).stream().map(VueEvenement::de).toList();
    }

    /**
     * Le suivi public d'un colis, par son numéro.
     *
     * <p>⚠️ <b>Route publique, et volontairement pauvre.</b> Un numéro de suivi
     * circule par SMS, par WhatsApp, sur un bordereau photographié : il ne
     * prouve rien sur l'identité de celui qui le présente.</p>
     *
     * <p>On renvoie donc le <b>parcours</b> — où est le colis, depuis quand —
     * et rien d'autre. Ni le contenu, ni le montant, ni le nom du
     * destinataire, ni l'identité de l'agent qui a scanné
     * ({@link VueEvenement#publique}). C'est exactement ce qu'affiche
     * n'importe quel transporteur, et pour la même raison.</p>
     */
    @GetMapping("/suivi/{numeroSuivi}")
    public List<VueEvenement> suivi(@PathVariable String numeroSuivi) {
        return expeditions.suivrePar(numeroSuivi).stream()
                .map(VueEvenement::publique)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Le retrait
    // -------------------------------------------------------------------------

    /**
     * Prépare le retrait : génère le code que le client présentera.
     *
     * <p>Le code n'est renvoyé qu'ici, à destination du client propriétaire.
     * Partout ailleurs il est masqué : le présenter suffit à repartir avec la
     * marchandise.</p>
     */
    @PostMapping("/{id}/retrait")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RETRAIT_CONSULTER')")
    public VueRetrait preparerRetrait(@PathVariable Long id,
                                      @Valid @RequestBody DemandeRetrait demande) {
        return VueRetrait.de(expeditions.preparerRetrait(id, demande.clientId()));
    }

    /**
     * L'agent confirme la remise en main propre.
     *
     * <p>Le code voyage dans le <b>corps</b> de la requête, jamais dans l'URL :
     * une URL se retrouve dans les journaux du serveur, dans l'historique du
     * navigateur et dans l'en-tête {@code Referer}. Un secret n'a rien à y
     * faire.</p>
     */
    @PostMapping("/retraits/confirmation")
    @PreAuthorize("hasAuthority('RETRAIT_CONFIRMER')")
    public VueRetrait confirmerRetrait(@Valid @RequestBody DemandeCodeRetrait demande,
                                       @AuthenticationPrincipal Jwt jeton) {
        return VueRetrait.sansCode(
                expeditions.confirmerRetrait(demande.codeRetrait(), utilisateur(jeton)));
    }

    @PostMapping("/retraits/refus")
    @PreAuthorize("hasAuthority('RETRAIT_REFUSER')")
    public VueRetrait refuserRetrait(@Valid @RequestBody DemandeRefusRetrait demande) {
        return VueRetrait.sansCode(
                expeditions.refuserRetrait(demande.codeRetrait(), demande.motif()));
    }

    // -------------------------------------------------------------------------

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    public record DemandeExpedition(
            @NotNull(message = "La commande est obligatoire.") Long commandeId,
            @NotNull(message = "Le lieu de départ est obligatoire.") Long lieuDepartId,
            @NotNull(message = "Le point de récupération est obligatoire.") Long pointRecuperationId,

            /* L'itinéraire est facultatif : une expédition directe n'en a pas. */
            Long itineraireId) {
    }

    public record DemandeColis(
            @NotBlank(message = "Le numéro de suivi est obligatoire.")
            @Size(max = 50, message = "Numéro de suivi trop long.")
            String numeroSuivi) {
    }

    public record DemandeLigneColis(
            @NotNull(message = "La ligne de commande est obligatoire.") Long ligneCommandeId,
            @Min(value = 1, message = "La quantité doit être d'au moins 1.") int quantite) {
    }

    public record DemandeEvenement(
            @NotNull(message = "Le lieu est obligatoire.") Long lieuId,
            @NotNull(message = "Le type d'événement est obligatoire.") TypeEvenement type,
            @Size(max = 500, message = "Observation trop longue.") String observation) {
    }

    public record DemandeRetrait(
            @NotNull(message = "Le client est obligatoire.") Long clientId) {
    }

    public record DemandeCodeRetrait(
            @NotBlank(message = "Le code de retrait est obligatoire.")
            @Size(max = 50, message = "Code de retrait invalide.")
            String codeRetrait) {
    }

    public record DemandeRefusRetrait(
            @NotBlank(message = "Le code de retrait est obligatoire.")
            @Size(max = 50, message = "Code de retrait invalide.")
            String codeRetrait,

            @NotBlank(message = "Le motif du refus est obligatoire.")
            @Size(max = 500, message = "Motif trop long.")
            String motif) {
    }
}
