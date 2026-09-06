package com.garah.api.surveillance.web;

import com.garah.api.surveillance.domaine.EvaluationRisque;
import com.garah.api.surveillance.domaine.ServiceAudit;
import com.garah.api.surveillance.domaine.ServiceScoreRisque;
import com.garah.api.surveillance.domaine.VueAlerte;
import com.garah.api.surveillance.domaine.VueAudit;
import com.garah.api.surveillance.domaine.VueScoreRisque;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Surveillance, score de risque et journal d'audit.
 *
 * <p>⚠️ <b>Tout ce contrôleur appartient au module SECURITE</b>, donc au seul
 * SuperAdmin : {@code ServiceAuthentification} exclut explicitement ce module
 * des permissions d'un Admin.</p>
 *
 * <p>Ce n'est pas de la méfiance envers les Admins, c'est une séparation des
 * pouvoirs. Celui qui administre les utilisateurs ne doit pas pouvoir effacer
 * la trace de ce qu'il a fait — sinon le journal d'audit ne prouve plus
 * rien.</p>
 */
@RestController
@RequestMapping("/api/surveillance")
public class ControleurSurveillance {

    private static final int TAILLE_MAX = 200;

    private final ServiceScoreRisque risques;
    private final ServiceAudit audit;

    public ControleurSurveillance(ServiceScoreRisque risques, ServiceAudit audit) {
        this.risques = risques;
        this.audit = audit;
    }

    // -------------------------------------------------------------------------
    // Le risque client
    // -------------------------------------------------------------------------

    /**
     * Évalue un client <b>sans rien enregistrer</b>.
     *
     * <p>Utile pour consulter un dossier sans polluer l'historique : une
     * lecture ne doit pas créer une ligne de score. Sinon consulter dix fois le
     * même client produirait dix scores identiques, et la courbe d'évolution
     * deviendrait illisible.</p>
     */
    @GetMapping("/clients/{clientId}/risque")
    @PreAuthorize("hasAuthority('SURVEILLANCE_CONSULTER_RISQUE')")
    public EvaluationRisque evaluer(@PathVariable Long clientId) {
        return risques.evaluer(clientId);
    }

    /** Calcule ET archive le score — l'évaluation qui compte. */
    @PostMapping("/clients/{clientId}/risque")
    @PreAuthorize("hasAuthority('SURVEILLANCE_CONSULTER_RISQUE')")
    public VueScoreRisque calculer(@PathVariable Long clientId) {
        return VueScoreRisque.de(risques.calculerEtEnregistrer(clientId));
    }

    @GetMapping("/clients/{clientId}/risque/historique")
    @PreAuthorize("hasAuthority('SURVEILLANCE_CONSULTER_RISQUE')")
    public List<VueScoreRisque> historique(@PathVariable Long clientId) {
        return risques.historique(clientId).stream().map(VueScoreRisque::de).toList();
    }

    // -------------------------------------------------------------------------
    // Les alertes
    // -------------------------------------------------------------------------

    @GetMapping("/alertes")
    @PreAuthorize("hasAuthority('SURVEILLANCE_TRAITER_ALERTE')")
    public List<VueAlerte> fileDAlertes() {
        return risques.fileDAlertes().stream().map(VueAlerte::de).toList();
    }

    /**
     * Tranche une alerte : confirmée ou écartée.
     *
     * <p>La décision est <b>toujours</b> motivée, et l'auteur enregistré. Une
     * alerte écartée sans explication est indistinguable d'une alerte oubliée
     * — et c'est précisément ce qu'on cherchera à savoir le jour où la fraude
     * qu'elle signalait aura abouti.</p>
     */
    @PostMapping("/alertes/{alerteId}/decision")
    @PreAuthorize("hasAuthority('SURVEILLANCE_TRAITER_ALERTE')")
    public VueAlerte trancher(@PathVariable Long alerteId,
                              @Valid @RequestBody DemandeDecision demande,
                              @AuthenticationPrincipal Jwt jeton) {
        return VueAlerte.de(risques.trancher(alerteId, demande.confirmee(),
                Long.valueOf(jeton.getSubject()), demande.decision()));
    }

    // -------------------------------------------------------------------------
    // Le journal d'audit
    // -------------------------------------------------------------------------

    /**
     * L'historique des modifications d'un objet.
     *
     * <p>« Qui a changé ce prix, et quand ? » — la question qui n'a de réponse
     * que si on l'a préparée à l'avance.</p>
     */
    @GetMapping("/audit/{entite}/{entiteId}")
    @PreAuthorize("hasAuthority('AUDIT_CONSULTER')")
    public List<VueAudit> historiqueDe(@PathVariable String entite,
                                       @PathVariable Long entiteId) {
        return audit.historiqueDe(entite, entiteId).stream().map(VueAudit::de).toList();
    }

    /** Tout ce qu'un utilisateur a fait. L'autre sens de la même question. */
    @GetMapping("/audit/acteurs/{utilisateurId}")
    @PreAuthorize("hasAuthority('AUDIT_CONSULTER')")
    public Page<VueAudit> actionsDe(@PathVariable Long utilisateurId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int taille) {
        return audit.actionsDe(utilisateurId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)))
                .map(VueAudit::de);
    }

    public record DemandeDecision(
            @NotNull(message = "Il faut dire si l'alerte est confirmée ou écartée.")
            Boolean confirmee,

            @NotBlank(message = "La décision doit être motivée.")
            @Size(max = 1000, message = "Décision trop longue.")
            String decision) {
    }
}
