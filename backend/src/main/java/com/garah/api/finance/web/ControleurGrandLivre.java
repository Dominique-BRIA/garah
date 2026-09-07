package com.garah.api.finance.web;

import com.garah.api.finance.domaine.ServiceGrandLivre;
import com.garah.api.finance.domaine.SoldeMarchand;
import com.garah.api.finance.domaine.VueEcriture;
import com.garah.api.finance.domaine.VueReglement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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

import java.math.BigDecimal;
import java.util.Map;

/**
 * Le grand livre marchand : ce que GARAH doit à chaque partenaire.
 *
 * <p>🎯 <b>Le solde n'est stocké nulle part.</b> Il est la somme des écritures,
 * recalculée à chaque lecture. Un solde stocké finit toujours par mentir : il
 * suffit d'une écriture ajoutée par un chemin qui a oublié de le mettre à
 * jour, et l'écart ne se voit qu'au moment de payer quelqu'un.</p>
 */
@RestController
@RequestMapping("/api/finance/marchands")
public class ControleurGrandLivre {

    private static final int TAILLE_MAX = 200;

    private final ServiceGrandLivre grandLivre;

    public ControleurGrandLivre(ServiceGrandLivre grandLivre) {
        this.grandLivre = grandLivre;
    }

    /**
     * Qui doit-on payer, et combien.
     *
     * <p>C'est la question du début de mois, et rien n'y répondait : il fallait
     * <b>connaître</b> un marchand pour demander son solde, donc les parcourir
     * un par un.</p>
     *
     * <p>Déclarée avant {@code /{marchandId}/solde} par lisibilité seulement —
     * Spring classe un segment littéral avant une variable, l'ordre ici ne
     * change rien.</p>
     */
    @GetMapping("/soldes")
    @PreAuthorize("hasAuthority('DETTE_MARCHAND_CONSULTER')")
    public Page<SoldeMarchand> soldes(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int taille) {
        return grandLivre.soldes(
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, 100)));
    }

    /** Le solde courant d'un marchand — la somme de ses écritures. */
    @GetMapping("/{marchandId}/solde")
    @PreAuthorize("hasAuthority('DETTE_MARCHAND_CONSULTER')")
    public Map<String, BigDecimal> solde(@PathVariable Long marchandId) {
        return Map.of("solde", grandLivre.solde(marchandId));
    }

    /**
     * Le détail des écritures, paginé.
     *
     * <p>Paginé <b>obligatoirement</b> : un marchand actif depuis un an a des
     * dizaines de milliers d'écritures, et les charger d'un coup mettrait
     * l'instance à genoux.</p>
     */
    @GetMapping("/{marchandId}/ecritures")
    @PreAuthorize("hasAuthority('HISTORIQUE_FINANCIER_MARCHAND_CONSULTER')")
    public Page<VueEcriture> ecritures(@PathVariable Long marchandId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int taille) {
        return grandLivre.detail(marchandId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)))
                .map(VueEcriture::de);
    }

    // -------------------------------------------------------------------------
    // Régler un marchand
    // -------------------------------------------------------------------------

    /**
     * Prépare un règlement — il n'est pas encore payé.
     *
     * <p>Deux temps, et c'est essentiel : {@code preparer} inscrit l'intention,
     * {@code confirmer} acte le versement réel avec sa référence bancaire. Les
     * fusionner ferait qu'un virement raté laisserait quand même une dette
     * soldée dans nos livres.</p>
     */
    @PostMapping("/{marchandId}/reglements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('REGLEMENT_MARCHAND_CREER')")
    public VueReglement preparer(@PathVariable Long marchandId,
                                 @Valid @RequestBody DemandeReglement demande,
                                 @AuthenticationPrincipal Jwt jeton) {
        return VueReglement.de(grandLivre.preparer(
                marchandId, demande.montant(), demande.moyen(), utilisateur(jeton)));
    }

    /**
     * Acte le versement.
     *
     * <p>La référence est obligatoire — c'est elle, et elle seule, qui permet
     * d'arbitrer un litige « je n'ai jamais été payé » six mois plus tard.</p>
     */
    @PostMapping("/reglements/{reglementId}/confirmation")
    @PreAuthorize("hasAuthority('REGLEMENT_MARCHAND_MODIFIER')")
    public VueReglement confirmer(@PathVariable Long reglementId,
                                  @Valid @RequestBody DemandeConfirmation demande) {
        return VueReglement.de(grandLivre.confirmer(reglementId, demande.reference()));
    }

    @PostMapping("/reglements/{reglementId}/annulation")
    @PreAuthorize("hasAuthority('REGLEMENT_MARCHAND_ANNULER')")
    public VueReglement annuler(@PathVariable Long reglementId) {
        return VueReglement.de(grandLivre.annuler(reglementId));
    }

    /**
     * Une écriture manuelle de correction.
     *
     * <p>⚠️ <b>La route la plus sensible de ce contrôleur.</b> Elle permet
     * d'écrire n'importe quel montant, dans n'importe quel sens, dans les
     * comptes d'un marchand. Le libellé est obligatoire, et l'auteur est
     * enregistré : une écriture qu'on ne peut pas expliquer six mois plus tard
     * est une écriture qu'il ne fallait pas passer.</p>
     *
     * <p>On ne corrige jamais une écriture existante — on en ajoute une qui
     * l'annule. Un grand livre ne se réécrit pas.</p>
     */
    @PostMapping("/{marchandId}/ajustements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('REGLE_COMMISSION_GERER')")
    public VueEcriture ajuster(@PathVariable Long marchandId,
                               @Valid @RequestBody DemandeAjustement demande,
                               @AuthenticationPrincipal Jwt jeton) {
        return VueEcriture.de(grandLivre.ajuster(
                marchandId, demande.montantSigne(), demande.libelle(), utilisateur(jeton)));
    }

    // -------------------------------------------------------------------------

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    public record DemandeReglement(
            @NotNull(message = "Le montant est obligatoire.")
            @DecimalMin(value = "1", message = "Un règlement doit être strictement positif.")
            BigDecimal montant,

            @NotBlank(message = "Le moyen de règlement est obligatoire.")
            @Size(max = 30, message = "Moyen de règlement trop long.")
            String moyen) {
    }

    public record DemandeConfirmation(
            @NotBlank(message = "La référence du versement est obligatoire.")
            @Size(max = 100, message = "Référence trop longue.")
            String reference) {
    }

    public record DemandeAjustement(
            /*
             * SIGNÉ, contrairement à tout le reste : ici le signe EST
             * l'information. Positif crédite le marchand, négatif le débite.
             * Un champ « sens » séparé permettrait d'envoyer un montant négatif
             * avec un sens positif, et personne ne saurait quoi en faire.
             */
            @NotNull(message = "Le montant est obligatoire.")
            BigDecimal montantSigne,

            @NotBlank(message = "Le libellé est obligatoire : une écriture sans explication "
                    + "est ininterprétable.")
            @Size(max = 300, message = "Libellé trop long.")
            String libelle) {
    }
}
