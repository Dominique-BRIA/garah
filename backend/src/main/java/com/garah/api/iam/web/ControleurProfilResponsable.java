package com.garah.api.iam.web;

import com.garah.api.iam.domaine.ServiceProfilResponsable;
import com.garah.api.iam.domaine.VueCasUtilisation;
import com.garah.api.iam.domaine.VueProfil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Les profils metier — {@code categorie_responsable} dans le schema.
 *
 * <p>La route dit « profils » et non « categories » : ce dernier mot designe
 * deja les familles de produits, et l'employer pour deux choses sans rapport
 * oblige a demander « categorie de quoi ? » a chaque fois.</p>
 */
@RestController
@RequestMapping("/api/profils")
public class ControleurProfilResponsable {

    private final ServiceProfilResponsable profils;

    public ControleurProfilResponsable(ServiceProfilResponsable profils) {
        this.profils = profils;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CATEGORIE_RESPONSABLE_CONSULTER')")
    public List<VueProfil> lister() {
        return profils.lister();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORIE_RESPONSABLE_CONSULTER')")
    public VueProfil detail(@PathVariable Long id) {
        return profils.detail(id);
    }

    /**
     * Le catalogue des fonctionnalites attribuables.
     *
     * <p>Ce que l'ecran propose a cocher. Il vient de {@code cas_utilisation},
     * la table des fonctionnalites REELLEMENT implementees : un administrateur
     * ne peut donc pas inventer une permission qui n'existe nulle part dans le
     * code.</p>
     */
    @GetMapping("/fonctionnalites")
    @PreAuthorize("hasAuthority('CATEGORIE_RESPONSABLE_CONSULTER')")
    public List<VueCasUtilisation> fonctionnalites() {
        return profils.fonctionnalites();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CATEGORIE_RESPONSABLE_CREER')")
    public VueProfil creer(@Valid @RequestBody DemandeProfil demande) {
        return profils.creer(demande.nom(), demande.description(), demande.permissions());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORIE_RESPONSABLE_MODIFIER')")
    public VueProfil modifier(@PathVariable Long id, @Valid @RequestBody DemandeProfil demande) {
        return profils.modifier(id, demande.nom(), demande.description(), demande.permissions());
    }

    /**
     * Desactiver, jamais supprimer.
     *
     * <p>Des responsables portent ce profil. Le supprimer les priverait
     * silencieusement de leurs droits. Desactiver n'enleve rien a ceux qui
     * l'ont deja : cela empeche seulement d'y affecter quelqu'un de nouveau.</p>
     */
    @PostMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('CATEGORIE_RESPONSABLE_MODIFIER')")
    public VueProfil activer(@PathVariable Long id) {
        return profils.changerStatut(id, true);
    }

    @DeleteMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('CATEGORIE_RESPONSABLE_MODIFIER')")
    public VueProfil desactiver(@PathVariable Long id) {
        return profils.changerStatut(id, false);
    }

    public record DemandeProfil(
            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 100, message = "Le nom ne peut pas depasser 100 caracteres.")
            String nom,

            String description,

            @NotEmpty(message = "Choisissez au moins une fonctionnalite.")
            List<String> permissions) {
    }
}
