package com.garah.api.serviceclient.web;

import com.garah.api.serviceclient.domaine.ResumeConversation;
import com.garah.api.serviceclient.domaine.SensProposition;
import com.garah.api.serviceclient.domaine.ServiceConversation;
import com.garah.api.serviceclient.domaine.ServiceNegociation;
import com.garah.api.serviceclient.domaine.StatutConversation;
import com.garah.api.serviceclient.domaine.VueConversation;
import com.garah.api.serviceclient.domaine.VueEvaluation;
import com.garah.api.serviceclient.domaine.VueMessage;
import com.garah.api.serviceclient.domaine.VueProposition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Les conversations client, et la négociation de prix qui s'y déroule.
 *
 * <p>La négociation n'a pas de routes séparées : une proposition de prix vit
 * <b>dans</b> une conversation, et l'exposer ailleurs ferait perdre son
 * contexte. C'est aussi ce qui garantit qu'on ne peut pas proposer un prix
 * sans qu'un échange l'ait précédé.</p>
 */
@RestController
@RequestMapping("/api/conversations")
public class ControleurConversation {

    /** Borne la taille de page : une URL se rallonge a la main en une seconde. */
    private static final int TAILLE_MAX = 100;

    /**
     * Durée de validité d'une proposition de prix.
     *
     * <p>Elle est fixée <b>par le serveur</b>, jamais par l'appelant : un
     * champ « validité » dans la requête permettrait à un client de s'accorder
     * un prix négocié valable dix ans. C'est l'invariant I-33 vu par l'autre
     * bout — expirer sert à quelque chose seulement si la durée n'est pas
     * choisie par celui qui en profite.</p>
     *
     * <p>Sept jours : assez pour qu'un client réfléchisse et revienne, assez
     * court pour que le tarif public reste la référence.</p>
     */
    private static final Duration VALIDITE = Duration.ofDays(7);

    private final ServiceConversation conversations;
    private final ServiceNegociation negociation;

    public ControleurConversation(ServiceConversation conversations,
                                  ServiceNegociation negociation) {
        this.conversations = conversations;
        this.negociation = negociation;
    }

    // -------------------------------------------------------------------------
    // Le client
    // -------------------------------------------------------------------------

    /** Ouvre une conversation. Le premier message part avec. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VueConversation ouvrir(@Valid @RequestBody DemandeOuverture demande,
                                  @AuthenticationPrincipal Jwt jeton) {
        return VueConversation.resume(
                conversations.ouvrir(utilisateur(jeton), demande.sujet(), demande.premierMessage()));
    }

    /**
     * Le fil complet d'une conversation.
     *
     * <p>⚠️ <b>Aucune permission n'est exigée — donc le contrôle de propriété
     * est la SEULE barrière.</b> Un client n'a aucun droit au sens du
     * référentiel : son accès repose sur la propriété de ses données
     * (chapitre 08). Retirer {@code exigerAcces} rouvrirait toutes les
     * conversations à tout compte connecté.</p>
     */
    @GetMapping("/{id}")
    public VueConversation fil(@PathVariable Long id,
                               @AuthenticationPrincipal Jwt jeton) {
        conversations.exigerAcces(id, utilisateur(jeton), estClient(jeton));
        return conversations.vue(id);
    }

    /**
     * Mes conversations.
     *
     * <p>⚠️ Aucune permission, comme le fil : le filtre est le porteur du
     * jeton, jamais un paramètre. Une liste « mienne » filtrée par un
     * identifiant reçu du navigateur est la liste de qui veut bien
     * l'écrire.</p>
     */
    @GetMapping("/miennes")
    public Page<VueConversation> miennes(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int taille,
                                         @AuthenticationPrincipal Jwt jeton) {
        return conversations.miennes(utilisateur(jeton),
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)));
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public VueMessage repondre(@PathVariable Long id,
                               @Valid @RequestBody DemandeMessage demande,
                               @AuthenticationPrincipal Jwt jeton) {
        conversations.exigerAcces(id, utilisateur(jeton), estClient(jeton));
        return VueMessage.de(
                conversations.repondre(id, utilisateur(jeton), demande.contenu()), id);
    }

    /**
     * Note la qualité de l'échange, une fois la conversation fermée.
     *
     * <p>Fermée seulement : demander un avis sur un problème non résolu ne
     * mesure pas le service, il mesure l'agacement.</p>
     */
    @PostMapping("/{id}/evaluation")
    @ResponseStatus(HttpStatus.CREATED)
    public VueEvaluation evaluer(@PathVariable Long id,
                                 @Valid @RequestBody DemandeEvaluation demande,
                                 @AuthenticationPrincipal Jwt jeton) {
        conversations.exigerAcces(id, utilisateur(jeton), estClient(jeton));
        // La conversion en DTO a lieu dans le service : lire un getter de
        // l'entité depuis la couche web violerait la règle d'architecture du
        // chapitre 06 — et ArchUnit l'a effectivement refusé.
        return conversations.evaluerEtResumer(id, demande.note(), demande.commentaire());
    }

    // -------------------------------------------------------------------------
    // Le responsable
    // -------------------------------------------------------------------------

    /** La file d'attente : ce qui n'est affecté à personne. */
    @GetMapping("/file-attente")
    @PreAuthorize("hasAuthority('CONVERSATION_CONSULTER')")
    public List<VueConversation> fileDAttente() {
        return conversations.fileDAttente().stream()
                .map(VueConversation::resume)
                .toList();
    }

    /**
     * Le nombre de conversations en attente, et rien d autre.
     *
     * <p>Pour le tableau de bord, qui n affiche qu un chiffre.
     * {@code /file-attente} rend la LISTE COMPLETE, dont le frontend ne lisait que
     * la taille — toutes les lignes traversaient le reseau pour rien.</p>
     */
    @GetMapping("/file-attente/nombre")
    @PreAuthorize("hasAuthority('CONVERSATION_CONSULTER')")
    public long nombreEnFileDAttente() {
        return conversations.nombreEnFileDAttente();
    }

    /**
     * La liste du back-office.
     *
     * <p>{@code miennes=true} ne renvoie que les dossiers de l'appelant. Le
     * responsable est lu dans le <b>jeton signé</b>, jamais dans un paramètre :
     * un {@code ?responsableId=} laisserait n'importe quel agent lire la file
     * d'un collègue, et le classer comme sien.</p>
     *
     * <p>Les plus <b>anciennes</b> d'abord : une conversation qui traîne est
     * un client qui attend. Même choix que les réclamations.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('CONVERSATION_CONSULTER')")
    public Page<ResumeConversation> lister(
            @RequestParam(required = false) StatutConversation statut,
            @RequestParam(defaultValue = "false") boolean miennes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille,
            @AuthenticationPrincipal Jwt jeton) {

        return conversations.administration(statut,
                miennes ? utilisateur(jeton) : null,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, 100)));
    }

    /**
     * « Je prends cette conversation. »
     *
     * <p>Le service refuse si quelqu'un a été plus rapide — un
     * {@code 409 Conflict}, pas une erreur de l'utilisateur : c'est le monde
     * qui a changé entre l'affichage de la liste et le clic.</p>
     */
    @PostMapping("/{id}/affectation")
    @PreAuthorize("hasAuthority('CONVERSATION_PRENDRE')")
    public VueConversation prendre(@PathVariable Long id,
                                   @AuthenticationPrincipal Jwt jeton) {
        return VueConversation.resume(conversations.prendre(id, utilisateur(jeton)));
    }

    @PostMapping("/{id}/reaffectation")
    @PreAuthorize("hasAuthority('CONVERSATION_REAFFECTER')")
    public VueConversation reaffecter(@PathVariable Long id,
                                      @Valid @RequestBody DemandeReaffectation demande,
                                      @AuthenticationPrincipal Jwt jeton) {
        return VueConversation.resume(conversations.reaffecter(
                id, demande.responsableId(), utilisateur(jeton), demande.motif()));
    }

    @PostMapping("/{id}/fermeture")
    @PreAuthorize("hasAuthority('CONVERSATION_FERMER')")
    public VueConversation fermer(@PathVariable Long id) {
        return VueConversation.resume(conversations.fermer(id));
    }

    // -------------------------------------------------------------------------
    // La négociation
    // -------------------------------------------------------------------------

    @GetMapping("/{id}/propositions")
    public List<VueProposition> propositions(@PathVariable Long id,
                                             @AuthenticationPrincipal Jwt jeton) {
        conversations.exigerAcces(id, utilisateur(jeton), estClient(jeton));
        return negociation.fil(id).stream().map(VueProposition::de).toList();
    }

    /**
     * Propose un prix.
     *
     * <p>Le sens (client ou vendeur) est déduit du type porté par le jeton, pas
     * d'un champ de la requête : un client qui enverrait
     * {@code sens: "VENDEUR"} s'accorderait lui-même une remise.</p>
     */
    @PostMapping("/{id}/propositions")
    @ResponseStatus(HttpStatus.CREATED)
    public VueProposition proposer(@PathVariable Long id,
                                   @Valid @RequestBody DemandeProposition demande,
                                   @AuthenticationPrincipal Jwt jeton) {
        conversations.exigerAcces(id, utilisateur(jeton), estClient(jeton));
        return VueProposition.de(negociation.proposer(
                id, demande.varianteId(), demande.quantite(),
                demande.prixUnitaire(), utilisateur(jeton),
                sens(jeton), VALIDITE));
    }

    @PostMapping("/propositions/{propositionId}/contre-proposition")
    @ResponseStatus(HttpStatus.CREATED)
    public VueProposition contreProposer(@PathVariable Long propositionId,
                                         @Valid @RequestBody DemandePrix demande,
                                         @AuthenticationPrincipal Jwt jeton) {
        exigerAccesALaProposition(propositionId, jeton);
        return VueProposition.de(negociation.contreProposer(
                propositionId, demande.prixUnitaire(), utilisateur(jeton),
                sens(jeton), VALIDITE));
    }

    /**
     * Accepte une proposition.
     *
     * <p>⚠️ Sans le contrôle de propriété, n'importe quel client pouvait
     * accepter la proposition d'un autre en changeant un identifiant — et
     * figer à sa place un prix négocié.</p>
     */
    @PostMapping("/propositions/{propositionId}/acceptation")
    public VueProposition accepter(@PathVariable Long propositionId,
                                   @AuthenticationPrincipal Jwt jeton) {
        exigerAccesALaProposition(propositionId, jeton);
        return VueProposition.de(negociation.accepter(propositionId));
    }

    @PostMapping("/propositions/{propositionId}/refus")
    public VueProposition refuser(@PathVariable Long propositionId,
                                  @AuthenticationPrincipal Jwt jeton) {
        exigerAccesALaProposition(propositionId, jeton);
        return VueProposition.de(negociation.refuser(propositionId));
    }

    // -------------------------------------------------------------------------

    /**
     * Le droit de toucher à une proposition se juge sur sa <b>conversation</b>.
     *
     * <p>Ces routes ne portent qu'un identifiant de proposition. Il faut donc
     * remonter à la conversation qui la contient : c'est elle qui porte le
     * client propriétaire.</p>
     */
    private void exigerAccesALaProposition(Long propositionId, Jwt jeton) {
        conversations.exigerAcces(negociation.conversationDe(propositionId),
                utilisateur(jeton), estClient(jeton));
    }

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    /**
     * Le sens de la proposition, déduit du <b>type porté par le jeton</b>.
     *
     * <p>⚠️ Jamais d'un champ de la requête. Un client qui pourrait écrire
     * {@code sens: "RESPONSABLE"} contournerait le seul garde-fou de la
     * négociation : le contrôle « une proposition vendeur ne dépasse pas le
     * tarif public » ne s'applique qu'au sens {@code RESPONSABLE}. Il
     * s'accorderait donc n'importe quelle remise, en une ligne de JSON.</p>
     */
    private static SensProposition sens(Jwt jeton) {
        return estClient(jeton) ? SensProposition.CLIENT : SensProposition.RESPONSABLE;
    }

    /**
     * Le type d'acteur, lu dans le jeton <b>signé</b>.
     *
     * <p>Un client ne voit que ses propres conversations ; un responsable, dont
     * le métier est de traiter celles des autres, passe. Ses droits à lui sont
     * contrôlés par les {@code @PreAuthorize} des routes qui lui sont
     * réservées.</p>
     */
    private static boolean estClient(Jwt jeton) {
        return "CLIENT".equals(jeton.getClaimAsString("type"));
    }

    public record DemandeOuverture(
            @NotBlank(message = "Le sujet est obligatoire.")
            @Size(max = 200, message = "Le sujet ne peut pas dépasser 200 caractères.")
            String sujet,

            @NotBlank(message = "Le message est obligatoire.")
            @Size(max = 5000, message = "Le message est trop long.")
            String premierMessage) {
    }

    public record DemandeMessage(
            @NotBlank(message = "Le message ne peut pas être vide.")
            @Size(max = 5000, message = "Le message est trop long.")
            String contenu) {
    }

    public record DemandeEvaluation(
            @Min(value = 1, message = "La note va de 1 à 5.")
            @Max(value = 5, message = "La note va de 1 à 5.")
            int note,

            @Size(max = 2000, message = "Commentaire trop long.")
            String commentaire) {
    }

    public record DemandeReaffectation(
            @NotNull(message = "Le nouveau responsable est obligatoire.")
            Long responsableId,

            @NotBlank(message = "Le motif de réaffectation est obligatoire.")
            @Size(max = 500, message = "Motif trop long.")
            String motif) {
    }

    public record DemandeProposition(
            @NotNull(message = "La variante est obligatoire.")
            Long varianteId,

            @Min(value = 1, message = "La quantité doit être d'au moins 1.")
            @Max(value = 10000, message = "Quantité trop importante.")
            int quantite,

            @NotNull(message = "Le prix proposé est obligatoire.")
            @DecimalMin(value = "1", message = "Le prix doit être positif.")
            BigDecimal prixUnitaire) {
    }

    public record DemandePrix(
            @NotNull(message = "Le prix proposé est obligatoire.")
            @DecimalMin(value = "1", message = "Le prix doit être positif.")
            BigDecimal prixUnitaire) {
    }

}
