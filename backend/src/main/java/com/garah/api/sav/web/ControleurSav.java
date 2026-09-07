package com.garah.api.sav.web;

import com.garah.api.commerce.domaine.MoyenPaiement;
import com.garah.api.sav.domaine.EtatArticle;
import com.garah.api.sav.domaine.ResumeReclamation;
import com.garah.api.sav.domaine.ResumeRetour;
import com.garah.api.sav.domaine.ServiceReclamation;
import com.garah.api.sav.domaine.ServiceRetour;
import com.garah.api.sav.domaine.StatutReclamation;
import com.garah.api.sav.domaine.StatutRetour;
import com.garah.api.sav.domaine.VueReclamation;
import com.garah.api.sav.domaine.VueRetour;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Le service après-vente : réclamations et retours.
 *
 * <p>La réclamation est la <b>seule voie de recours</b> du client après
 * paiement (D-12) : il ne peut plus annuler lui-même, un humain examine. Cette
 * route n'est donc pas un accessoire — c'est la contrepartie de la règle
 * d'annulation, et sans elle celle-ci serait simplement brutale.</p>
 */
@RestController
@RequestMapping("/api/sav")
public class ControleurSav {

    private static final int TAILLE_MAX = 100;

    private final ServiceReclamation reclamations;
    private final ServiceRetour retours;

    public ControleurSav(ServiceReclamation reclamations, ServiceRetour retours) {
        this.reclamations = reclamations;
        this.retours = retours;
    }

    // -------------------------------------------------------------------------
    // Réclamations — le client
    // -------------------------------------------------------------------------

    @PostMapping("/reclamations")
    @ResponseStatus(HttpStatus.CREATED)
    public VueReclamation ouvrir(@Valid @RequestBody DemandeReclamation demande,
                                 @AuthenticationPrincipal Jwt jeton) {
        return VueReclamation.de(reclamations.ouvrir(
                client(jeton), demande.commandeId(), demande.motif(), demande.description()));
    }

    @GetMapping("/reclamations/miennes")
    public Page<VueReclamation> mesReclamations(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int taille,
                                                @AuthenticationPrincipal Jwt jeton) {
        return reclamations.mesReclamations(client(jeton),
                        PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)))
                .map(VueReclamation::de);
    }

    // -------------------------------------------------------------------------
    // Réclamations — le back-office
    // -------------------------------------------------------------------------

    /**
     * Le raccourci du tableau de bord : ce qui n'est pris en charge par
     * personne.
     *
     * <p>Distincte de {@link #listerReclamations} et volontairement : celle-ci
     * répond à « combien de dossiers n'ont <b>aucun</b> propriétaire ? », et
     * c'est ce chiffre-là qu'on met sur une carte. La liste complète répond à
     * « où en est le dossier de ce client ? », qui est une autre question.</p>
     */
    @GetMapping("/reclamations/a-traiter")
    @PreAuthorize("hasAuthority('RECLAMATION_CONSULTER')")
    public List<VueReclamation> aTraiter() {
        return reclamations.aTraiter().stream().map(VueReclamation::de).toList();
    }

    /**
     * La liste du back-office.
     *
     * <p>Les plus <b>anciennes</b> d'abord — l'inverse des listes de
     * catalogue, et c'est voulu : une réclamation qui traîne est un client qui
     * s'énerve, et c'est celle-là qu'il faut voir en haut.</p>
     *
     * <p>La recherche porte sur le numéro de réclamation <b>et</b> sur celui
     * de la commande : quand un client rappelle, il donne le second — il n'a
     * souvent jamais noté le premier.</p>
     */
    @GetMapping("/reclamations")
    @PreAuthorize("hasAuthority('RECLAMATION_CONSULTER')")
    public Page<ResumeReclamation> listerReclamations(
            @RequestParam(required = false) StatutReclamation statut,
            @RequestParam(required = false) String recherche,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille) {

        return reclamations.administration(statut, recherche,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)));
    }

    /**
     * Une réclamation, description comprise.
     *
     * <p>Contrairement au routage d'un navigateur, l'ordre de déclaration ne
     * compte pas ici : Spring classe un segment <b>littéral</b> avant une
     * variable, donc {@code /reclamations/a-traiter} et
     * {@code /reclamations/miennes} restent atteignables même déclarés avant
     * ce {@code {id}}. Inutile de les réordonner « par sécurité ».</p>
     */
    @GetMapping("/reclamations/{id}")
    @PreAuthorize("hasAuthority('RECLAMATION_CONSULTER')")
    public VueReclamation detailReclamation(@PathVariable Long id) {
        return reclamations.detail(id);
    }

    @PostMapping("/reclamations/{id}/prise-en-charge")
    @PreAuthorize("hasAuthority('RECLAMATION_PRENDRE_EN_CHARGE')")
    public VueReclamation prendreEnCharge(@PathVariable Long id,
                                          @AuthenticationPrincipal Jwt jeton) {
        return VueReclamation.de(reclamations.prendreEnCharge(id, utilisateur(jeton)));
    }

    /**
     * Tranche une réclamation.
     *
     * <p>{@code favorable} décide du sens. Une résolution favorable n'entraîne
     * <b>pas</b> automatiquement de remboursement : c'est une décision
     * distincte, prise par quelqu'un qui a la permission
     * {@code PAIEMENT_REMBOURSER}. Lier les deux ferait qu'un agent de SAV
     * déclencherait des mouvements d'argent sans en avoir le droit.</p>
     */
    @PostMapping("/reclamations/{id}/resolution")
    @PreAuthorize("hasAuthority('RECLAMATION_RESOUDRE')")
    public VueReclamation resoudre(@PathVariable Long id,
                                   @Valid @RequestBody DemandeResolution demande) {
        return VueReclamation.de(reclamations.resoudre(id, demande.favorable()));
    }

    // -------------------------------------------------------------------------
    // Retours
    // -------------------------------------------------------------------------

    /**
     * Le client demande à retourner des articles.
     *
     * <p>Il désigne des <b>lignes de commande</b>, pas des produits : c'est la
     * ligne qui porte le prix figé, et donc le montant remboursable. Passer par
     * le produit obligerait à retrouver « à quel prix l'avait-il payé ? », qui
     * est exactement la question que la photographie des prix a supprimée.</p>
     */
    @PostMapping("/retours")
    @ResponseStatus(HttpStatus.CREATED)
    public VueRetour demanderRetour(@Valid @RequestBody DemandeRetour demande,
                                    @AuthenticationPrincipal Jwt jeton) {
        List<ServiceRetour.DemandeLigne> lignes = demande.lignes().stream()
                .map(l -> new ServiceRetour.DemandeLigne(
                        l.ligneCommandeId(), l.quantite(), l.etatArticle()))
                .toList();

        return VueRetour.resume(retours.demander(
                demande.commandeId(), client(jeton), demande.motif(), lignes));
    }

    /**
     * La liste du back-office.
     *
     * <p>Le filtre par statut n'est pas un confort : accepter, réceptionner et
     * valider un retour sont <b>trois métiers différents</b>, avec trois
     * permissions différentes. Sans filtre, chacun devrait parcourir toute la
     * liste pour trouver ce qui l'attend.</p>
     */
    @GetMapping("/retours")
    @PreAuthorize("hasAuthority('RETOUR_CONSULTER')")
    public Page<ResumeRetour> listerRetours(
            @RequestParam(required = false) StatutRetour statut,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille) {

        return retours.administration(statut,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)));
    }

    @GetMapping("/retours/{id}")
    @PreAuthorize("hasAuthority('RETOUR_CONSULTER')")
    public VueRetour detail(@PathVariable Long id) {
        return retours.vue(id);
    }

    @GetMapping("/retours/commande/{commandeId}")
    @PreAuthorize("hasAuthority('RETOUR_CONSULTER')")
    public List<VueRetour> pourCommande(@PathVariable Long commandeId) {
        return retours.pourCommande(commandeId).stream().map(VueRetour::resume).toList();
    }

    @PostMapping("/retours/{id}/acceptation")
    @PreAuthorize("hasAuthority('RETOUR_ACCEPTER')")
    public VueRetour accepter(@PathVariable Long id) {
        return VueRetour.resume(retours.accepter(id));
    }

    @PostMapping("/retours/{id}/refus")
    @PreAuthorize("hasAuthority('RETOUR_REFUSER')")
    public VueRetour refuser(@PathVariable Long id) {
        return VueRetour.resume(retours.refuser(id));
    }

    @PostMapping("/retours/{id}/reception")
    @PreAuthorize("hasAuthority('RETOUR_RECEPTIONNER')")
    public VueRetour receptionner(@PathVariable Long id) {
        return VueRetour.resume(retours.receptionner(id));
    }

    /**
     * Valide le retour : rembourse le client et débite le marchand.
     *
     * <p>Trois effets dans la même transaction — le remboursement, la
     * réintégration du stock pour les articles en bon état, et l'écriture au
     * grand livre marchand. Ce qui doit être vrai ensemble s'écrit ensemble.</p>
     *
     * <p>C'est pour cela que cette route exige {@code RETOUR_VALIDER} et non
     * {@code RETOUR_RECEPTIONNER} : réceptionner un colis et décider de rendre
     * l'argent ne sont pas le même métier.</p>
     */
    @PostMapping("/retours/{id}/validation")
    @PreAuthorize("hasAuthority('RETOUR_VALIDER')")
    public VueRetour valider(@PathVariable Long id,
                             @Valid @RequestBody DemandeValidationRetour demande) {
        return VueRetour.resume(retours.valider(id, demande.moyenRemboursement()));
    }

    @PostMapping("/retours/{id}/cloture")
    @PreAuthorize("hasAuthority('RETOUR_CLOTURER')")
    public VueRetour cloturer(@PathVariable Long id) {
        return VueRetour.resume(retours.cloturer(id));
    }

    // -------------------------------------------------------------------------

    private static Long client(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    public record DemandeReclamation(
            @NotNull(message = "La commande est obligatoire.") Long commandeId,

            @NotBlank(message = "Le motif est obligatoire.")
            @Size(max = 200, message = "Motif trop long.")
            String motif,

            @NotBlank(message = "La description est obligatoire.")
            @Size(max = 5000, message = "Description trop longue.")
            String description) {
    }

    public record DemandeResolution(
            @NotNull(message = "Le sens de la résolution est obligatoire.")
            Boolean favorable) {
    }

    public record DemandeRetour(
            @NotNull(message = "La commande est obligatoire.") Long commandeId,

            @NotBlank(message = "Le motif du retour est obligatoire.")
            @Size(max = 500, message = "Motif trop long.")
            String motif,

            @NotEmpty(message = "Un retour porte sur au moins une ligne.")
            @Size(max = 100, message = "Trop de lignes dans un seul retour.")
            @Valid
            List<LigneDemandee> lignes) {

        public record LigneDemandee(
                @NotNull(message = "La ligne de commande est obligatoire.")
                Long ligneCommandeId,

                @Min(value = 1, message = "La quantité doit être d'au moins 1.")
                int quantite,

                /*
                 * L'état déclaré par le client est une DÉCLARATION, pas un
                 * constat : il sera vérifié à la réception. C'est la raison
                 * pour laquelle valider() est une étape séparée — sinon un
                 * client déclarerait « neuf » tout ce qu'il renvoie cassé.
                 */
                @NotNull(message = "L'état de l'article est obligatoire.")
                EtatArticle etatArticle) {
        }
    }

    public record DemandeValidationRetour(
            @NotNull(message = "Le moyen de remboursement est obligatoire.")
            MoyenPaiement moyenRemboursement) {
    }
}
