package com.garah.api.commerce.web;

import com.garah.api.commerce.domaine.DetailCommande;
import com.garah.api.commerce.domaine.ResumeCommande;
import com.garah.api.commerce.domaine.ServiceCommande;
import com.garah.api.commerce.domaine.StatutCommande;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Les commandes — côté client et côté back-office.
 *
 * <p>Les deux publics ne partagent <b>aucune</b> route, et c'est délibéré. Un
 * seul endpoint conditionné par {@code if (estResponsable)} finit toujours par
 * laisser passer quelque chose : la règle d'accès devient une branche de code
 * au lieu d'être une annotation qu'on lit d'un coup d'oeil.</p>
 */
@RestController
@RequestMapping("/api/commandes")
public class ControleurCommande {

    private static final int TAILLE_MAX = 100;

    private final ServiceCommande commandes;

    public ControleurCommande(ServiceCommande commandes) {
        this.commandes = commandes;
    }

    // -------------------------------------------------------------------------
    // Le client
    // -------------------------------------------------------------------------

    /**
     * Passe commande à partir du panier.
     *
     * <p>C'est ici que tout se fige : prix, désignation, taux de TVA, taux de
     * commission et frais d'acheminement sont <b>photographiés</b> (D-10, D-11).
     * Le point de récupération aussi — il ne bougera plus (D-05).</p>
     *
     * <p>Le stock est réservé dans la même transaction, sans être décrémenté :
     * le paiement mobile money est asynchrone, et la marchandise doit être
     * tenue le temps que le client valide sur son téléphone (D-06).</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DetailCommande passer(@Valid @RequestBody DemandeCommande demande,
                                 @AuthenticationPrincipal Jwt jeton) {
        return commandes.passer(client(jeton), demande.pointRecuperationId(),
                langue(demande.langue(), jeton));
    }

    @GetMapping("/miennes")
    public Page<DetailCommande> mesCommandes(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int taille,
                                             @AuthenticationPrincipal Jwt jeton) {
        return commandes.mesCommandes(client(jeton),
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)));
    }

    /**
     * Le détail d'une de mes commandes.
     *
     * <p>Le service vérifie la propriété et répond « introuvable » — jamais
     * « interdit » — si la commande est celle d'un autre. Un 403 confirmerait
     * qu'elle existe, et parcourir les identifiants suffirait à reconstituer le
     * volume d'affaires de la plateforme.</p>
     */
    @GetMapping("/miennes/{id}")
    public DetailCommande maCommande(@PathVariable Long id,
                                     @AuthenticationPrincipal Jwt jeton) {
        return commandes.detailPourClient(id, client(jeton));
    }

    /**
     * Annuler — <b>uniquement</b> avant paiement (D-12).
     *
     * <p>Une fois la commande payée, le stock est engagé et l'acheminement vers
     * Bangui peut partir : le client ne peut plus défaire cela d'un clic. Sa
     * voie de recours est la réclamation.</p>
     *
     * <p>Le service refuse la transition depuis {@code PAYEE}, mais l'interface
     * doit <b>aussi</b> masquer le bouton : une règle métier invisible à
     * l'écran ne protège de rien, elle produit des réclamations.</p>
     */
    @PostMapping("/miennes/{id}/annulation")
    public DetailCommande annulerMaCommande(@PathVariable Long id,
                                            @AuthenticationPrincipal Jwt jeton) {
        // On lit d'abord AVEC le contrôle de propriété : sans cet appel,
        // n'importe qui annulerait la commande de n'importe qui.
        commandes.detailPourClient(id, client(jeton));
        return commandes.annuler(id, "Annulée par le client");
    }

    // -------------------------------------------------------------------------
    // Le back-office
    // -------------------------------------------------------------------------

    /**
     * La liste du back-office : toutes les commandes.
     *
     * <p>Distincte de {@code /miennes}, qui répond à « où en sont MES
     * commandes ? ». Servir les deux besoins par une seule route conditionnée
     * par « est-ce que l'appelant est un responsable ? » finirait un jour par
     * montrer à un client les commandes de tout le monde.</p>
     *
     * <p>{@code statut} filtre, {@code recherche} porte sur le numéro. Sans
     * filtre par statut, la question « qu'est-ce qui attend une action ? »
     * demanderait de parcourir toutes les pages.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('COMMANDE_CONSULTER_DETAILS')")
    public Page<ResumeCommande> lister(
            @RequestParam(required = false) StatutCommande statut,
            @RequestParam(required = false) String recherche,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille) {

        return commandes.administration(statut, recherche, PageRequest.of(
                Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('COMMANDE_CONSULTER_DETAILS')")
    public DetailCommande detail(@PathVariable Long id) {
        return commandes.detail(id);
    }

    /**
     * Fait avancer une commande dans son cycle de vie.
     *
     * <p>Une seule route pour toutes les transitions, parce que la machine à
     * états vit dans le service et nulle part ailleurs. Cinq routes
     * ({@code /preparation}, {@code /pret}, ...) dupliqueraient la question
     * « d'où peut-on venir ? » à cinq endroits.</p>
     */
    @PostMapping("/{id}/statut")
    @PreAuthorize("hasAuthority('COMMANDE_PREPARER')")
    public DetailCommande changerStatut(@PathVariable Long id,
                                        @Valid @RequestBody DemandeStatut demande) {
        return commandes.changerStatut(id, demande.statut());
    }

    /**
     * L'annulation a sa propre permission.
     *
     * <p>Annuler une commande payée entraîne un remboursement : ce n'est pas du
     * même ordre que de la faire passer de PAYEE à EN_PREPARATION. Un
     * préparateur doit pouvoir avancer une commande sans pouvoir défaire une
     * vente.</p>
     */
    @PostMapping("/{id}/annulation")
    @PreAuthorize("hasAuthority('COMMANDE_ANNULER')")
    public DetailCommande annuler(@PathVariable Long id,
                                  @Valid @RequestBody DemandeAnnulation demande) {
        return commandes.annuler(id, demande.motif());
    }

    // -------------------------------------------------------------------------

    private static Long client(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    /**
     * La langue de la commande : celle demandée, sinon celle du compte.
     *
     * <p>Elle est <b>figée</b> (D-08) : une facture émise en sango reste en
     * sango, même si le client change de préférence six mois plus tard.</p>
     */
    private static String langue(String demandee, Jwt jeton) {
        if (demandee != null && !demandee.isBlank()) {
            return demandee;
        }
        String duJeton = jeton.getClaimAsString("langue");
        return duJeton == null || duJeton.isBlank() ? "fr" : duJeton;
    }

    public record DemandeCommande(
            @NotNull(message = "Le point de récupération est obligatoire.")
            Long pointRecuperationId,

            @Pattern(regexp = "^$|^(fr|en|sg)$",
                     message = "La langue doit être fr, en ou sg.")
            String langue) {
    }

    public record DemandeStatut(
            @NotNull(message = "Le statut visé est obligatoire.")
            StatutCommande statut) {
    }

    public record DemandeAnnulation(
            /*
             * Obligatoire côté back-office : une annulation après paiement
             * entraîne un remboursement. Sans motif enregistré, personne ne
             * saura, six mois plus tard, pourquoi l'argent est reparti.
             */
            @NotBlank(message = "Le motif est obligatoire.")
            @Size(max = 500, message = "Motif trop long.")
            String motif) {
    }
}
