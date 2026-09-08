package com.garah.api.logistique.web;

import com.garah.api.logistique.domaine.MonRetrait;
import com.garah.api.logistique.domaine.ResumeExpedition;
import com.garah.api.logistique.domaine.ServiceExpedition;
import com.garah.api.logistique.domaine.StatutExpedition;
import com.garah.api.logistique.domaine.VueParcoursColis;
import com.garah.api.logistique.domaine.TypeEvenement;
import com.garah.api.logistique.domaine.VueComptoir;
import com.garah.api.logistique.domaine.VueEvenement;
import com.garah.api.logistique.domaine.VueExpedition;
import com.garah.api.logistique.domaine.VueLigneColis;
import com.garah.api.logistique.domaine.VueRetrait;
import com.garah.api.logistique.domaine.VueSuivi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    /**
     * Crée l'expédition d'une commande.
     *
     * <p>La <b>destination n'est pas dans le corps</b> : elle se déduit du
     * point de récupération choisi par le client en commandant. La faire
     * ressaisir donnerait le moyen d'expédier ailleurs que là où le client
     * viendra — et là où il a payé l'acheminement.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EXPEDITION_CREER')")
    public VueExpedition creer(@Valid @RequestBody DemandeExpedition demande) {
        return VueExpedition.resume(expeditions.creer(
                demande.commandeId(), demande.lieuDepartId(), demande.itineraireId()));
    }

    /**
     * La liste du back-office : toutes les expeditions.
     *
     * <p>Elle repond a deux questions : « qu'est-ce qui est en route ? » et
     * « qu'est-ce qui attend un depart ? ». Sans filtre par statut, il
     * faudrait parcourir toutes les pages pour repondre a la seconde.</p>
     *
     * <p>La recherche porte sur le numero d'expedition ET sur le numero de
     * commande : quand un client appelle, il donne le second, jamais le
     * premier.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('EXPEDITION_CONSULTER')")
    public Page<ResumeExpedition> lister(
            @RequestParam(required = false) StatutExpedition statut,
            @RequestParam(required = false) String recherche,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille) {

        return expeditions.administration(statut, recherche,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, 100)));
    }

    /**
     * Le parcours complet : chaque colis avec ses evenements dates.
     *
     * <p>🎯 C'est ce qui distingue un SUIVI d'un STATUT. « EN_TRANSIT » ne dit
     * pas ou ; « receptionne a Bertoua le 12/03 a 14 h » le dit, et reste vrai
     * meme quand le colis est reparti.</p>
     */
    @GetMapping("/{id}/parcours")
    @PreAuthorize("hasAuthority('EXPEDITION_CONSULTER_HISTORIQUE')")
    public List<VueParcoursColis> parcoursComplet(@PathVariable Long id) {
        return expeditions.parcoursComplet(id);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPEDITION_CONSULTER')")
    public VueExpedition detail(@PathVariable Long id) {
        return expeditions.vue(id);
    }

    /**
     * Ce qui est déjà parti pour cette commande.
     *
     * <p>Il peut y avoir <b>plusieurs</b> expéditions : une commande à deux
     * marchands part rarement d'un seul entrepôt le même jour. La fiche
     * commande a besoin de le savoir avant de proposer d'en créer une de plus
     * — sans quoi l'opérateur expédie deux fois la même marchandise.</p>
     */
    @GetMapping("/commandes/{commandeId}")
    @PreAuthorize("hasAuthority('EXPEDITION_CONSULTER')")
    public List<ResumeExpedition> parCommande(@PathVariable Long commandeId) {
        return expeditions.parCommande(commandeId);
    }

    /**
     * Mon code de retrait.
     *
     * <h2>🎯 La seule route de retrait sans autorité de back-office</h2>
     *
     * <p>Toutes les autres exigent {@code RETRAIT_CONSULTER} ou
     * {@code RETRAIT_CONFIRMER}. Le client, lui, n'a aucune autorité — il a
     * seulement un compte. Sans cette route, le code qui lui est destiné ne
     * pouvait lui parvenir qu'à la voix : le seul moyen de prouver une remise
     * circulait au téléphone.</p>
     *
     * <p>⚠️ <b>Pas de {@code PreAuthorize} ici, et ce n'est pas un oubli.</b>
     * Le contrôle n'est pas une autorité mais une <b>propriété</b> : le
     * service compare le porteur du jeton au client de la commande. Une
     * autorité dirait « ce rôle a le droit de voir des retraits » ; ce qu'il
     * faut dire, c'est « celui-ci a le droit de voir CE retrait-là ».</p>
     *
     * <p>Rend une <b>liste</b> : une commande passée chez deux marchands a deux
     * expéditions, donc deux codes, à retirer séparément. Vide tant que rien
     * n'est parti — ce n'est pas une erreur, c'est l'état d'une commande qu'on
     * vient de payer.</p>
     */
    @GetMapping("/commandes/{commandeId}/mon-retrait")
    public List<MonRetrait> monRetrait(@PathVariable Long commandeId,
                                       @AuthenticationPrincipal Jwt jeton) {
        return expeditions.mesRetraits(commandeId, utilisateur(jeton));
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
     *
     * <p>La réponse nomme les lieux au lieu de les numéroter : la liste des
     * lieux demande une authentification, et « lieu 12 » ne répond à personne.
     * Voir {@link VueSuivi}.</p>
     */
    @GetMapping("/suivi/{numeroSuivi}")
    public VueSuivi suivi(@PathVariable String numeroSuivi) {
        return expeditions.suiviPublic(numeroSuivi);
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
     *
     * <p><b>Sans corps de requête</b> : le destinataire se déduit de la
     * commande rattachée à l'expédition ({@code ServiceExpedition}). Il n'y a
     * rien à saisir, donc rien à se tromper en saisissant.</p>
     */
    @PostMapping("/{id}/retrait")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RETRAIT_CONSULTER')")
    public VueRetrait preparerRetrait(@PathVariable Long id) {
        return VueRetrait.de(expeditions.preparerRetrait(id));
    }

    /**
     * Le comptoir : ce que le code désigne, avant toute remise.
     *
     * <p>C'est un {@code POST} bien qu'il ne modifie rien, et c'est
     * délibéré : le code doit voyager dans le <b>corps</b>. En {@code GET}, il
     * finirait dans l'URL — donc dans les journaux du serveur, dans
     * l'historique du navigateur et dans l'en-tête {@code Referer}. Un secret
     * n'a rien à y faire.</p>
     */
    @PostMapping("/retraits/recherche")
    @PreAuthorize("hasAuthority('RETRAIT_CONFIRMER')")
    public VueComptoir chercherRetrait(@Valid @RequestBody DemandeCodeRetrait demande) {
        return expeditions.auComptoir(demande.codeRetrait());
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

    /**
     * Ce qu'il reste à saisir : d'où ça part, et par où.
     *
     * <p>Pas la destination — voir {@link #creer}. Ce qui se saisit, c'est le
     * <b>départ</b> : la même commande peut partir de Douala ou d'un stock
     * déjà consolidé à Bertoua, et ça, seul l'opérateur le sait.</p>
     */
    public record DemandeExpedition(
            @NotNull(message = "La commande est obligatoire.") Long commandeId,
            @NotNull(message = "Le lieu de départ est obligatoire.") Long lieuDepartId,

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
