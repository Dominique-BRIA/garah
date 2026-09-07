package com.garah.api.marchand.web;

import com.garah.api.marchand.domaine.ServiceRegleCommission;
import com.garah.api.marchand.domaine.VueRegleCommission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Les taux de commission.
 *
 * <p>Toutes les routes exigent {@code REGLE_COMMISSION_GERER} — <b>y compris
 * la lecture</b>. Un taux de commission n'est pas une donnée d'exploitation :
 * savoir ce que GARAH prélève à chaque marchand est une information
 * commerciale, et un agent de comptoir n'a aucune raison d'y accéder.</p>
 *
 * <p>La route vit sous {@code /api/marchands} et non sous {@code /api/finance}
 * : une règle de commission décide de ce qu'on prélève <b>à la vente</b>, pas
 * de ce qu'on règle ensuite. Le grand livre en est la conséquence, pas le
 * paramétrage.</p>
 */
@RestController
@RequestMapping("/api/marchands/regles-commission")
public class ControleurRegleCommission {

    private final ServiceRegleCommission regles;

    public ControleurRegleCommission(ServiceRegleCommission regles) {
        this.regles = regles;
    }

    /**
     * Toutes les règles, la plus spécifique en tête.
     *
     * <p>L'ordre est celui du <b>calcul</b>, pas un tri d'affichage : c'est ce
     * qui permet de comprendre pourquoi telle règle l'emporte.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('REGLE_COMMISSION_GERER')")
    public List<VueRegleCommission> lister() {
        return regles.lister();
    }

    /**
     * Ce qui s'appliquerait à une vente, aujourd'hui.
     *
     * <p>Les deux paramètres sont facultatifs : sans eux, on obtient le taux
     * général — celui que reçoit un marchand pour lequel rien n'a été
     * paramétré.</p>
     */
    @GetMapping("/simulation")
    @PreAuthorize("hasAuthority('REGLE_COMMISSION_GERER')")
    public ServiceRegleCommission.Simulation simuler(
            @RequestParam(required = false) Long marchandId,
            @RequestParam(required = false) Long categorieProduitId) {
        return regles.simuler(marchandId, categorieProduitId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('REGLE_COMMISSION_GERER')")
    public VueRegleCommission creer(@Valid @RequestBody DemandeRegle demande) {
        return regles.creer(demande.marchandId(), demande.categorieProduitId(),
                demande.taux(), demande.priorite(),
                demande.dateDebut(), demande.dateFin(),
                demande.confirmeTauxEleve());
    }

    /**
     * Ferme une règle. <b>Il n'y a pas de suppression.</b>
     *
     * <p>Les commandes passées ont figé leur taux, et l'écriture de commission
     * qui va avec doit rester explicable. Effacer la règle rendrait
     * inexplicable une ligne du grand livre vieille de six mois.</p>
     */
    @PostMapping("/{id}/fermeture")
    @PreAuthorize("hasAuthority('REGLE_COMMISSION_GERER')")
    public VueRegleCommission fermer(@PathVariable Long id,
                                     @RequestBody(required = false) DemandeFermeture demande) {
        return regles.fermer(id, demande == null ? null : demande.dateFin());
    }

    // -------------------------------------------------------------------------

    /**
     * @param marchandId          nul = <b>tous</b> les marchands.
     * @param categorieProduitId  nul = <b>toutes</b> les catégories.
     * @param confirmeTauxEleve   accepte un taux supérieur à 50 %. Le défaut
     *                            {@code false} est volontaire : la barrière ne
     *                            sert que si on ne la franchit pas par
     *                            distraction.
     */
    public record DemandeRegle(
            Long marchandId,
            Long categorieProduitId,

            @NotNull(message = "Le taux est obligatoire.")
            @DecimalMin(value = "0", message = "Un taux ne peut pas être négatif.")
            @DecimalMax(value = "100", message = "Un taux ne dépasse pas 100 %.")
            BigDecimal taux,

            @Min(value = 0, message = "La priorité ne peut pas être négative.")
            @Max(value = 1000, message = "Priorité trop élevée.")
            int priorite,

            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDebut,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFin,

            boolean confirmeTauxEleve) {
    }

    /** @param dateFin nul = aujourd'hui, donc effet immédiat. */
    public record DemandeFermeture(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFin) {
    }
}
