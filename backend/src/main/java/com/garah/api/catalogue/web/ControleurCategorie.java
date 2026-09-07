package com.garah.api.catalogue.web;

import com.garah.api.catalogue.domaine.ServiceCategorie;
import com.garah.api.catalogue.domaine.VueCategorie;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Les categories de produits.
 *
 * <p>Deuxieme maillon du catalogue : un produit exige une categorie autant
 * qu'un marchand.</p>
 */
@RestController
@RequestMapping("/api/categories")
public class ControleurCategorie {

    private final ServiceCategorie categories;

    public ControleurCategorie(ServiceCategorie categories) {
        this.categories = categories;
    }

    /**
     * L'arbre complet. PUBLIQUE : la vitrine en a besoin pour son menu.
     *
     * <p>Rien de confidentiel dans une categorie — c'est du contenu destine a
     * etre vu. Exiger un jeton fermerait la navigation du site public.</p>
     */
    @GetMapping
    public List<VueCategorie> arbre() {
        return categories.arbre();
    }

    /** La liste a plat, pour une liste deroulante du back-office. */
    @GetMapping("/plates")
    @PreAuthorize("hasAuthority('CATEGORIE_PRODUIT_GERER')")
    public List<VueCategorie> plates() {
        return categories.listePlate();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CATEGORIE_PRODUIT_GERER')")
    public VueCategorie creer(@Valid @RequestBody DemandeCategorie demande) {
        return categories.creer(demande.nom(), demande.parentId(),
                demande.ordre() == null ? 0 : demande.ordre());
    }

    /**
     * Renomme une categorie et change sa place dans la fratrie.
     *
     * <p>{@code parentId} est ignore s'il est envoye : deplacer une branche
     * demande de revalider la profondeur et l'absence de cycle pour toute la
     * descendance. C'est une autre operation.</p>
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORIE_PRODUIT_GERER')")
    public VueCategorie renommer(@PathVariable Long id,
                                 @Valid @RequestBody DemandeCategorie demande) {
        return categories.renommer(id, demande.nom(),
                demande.ordre() == null ? 0 : demande.ordre());
    }

    @PostMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('CATEGORIE_PRODUIT_GERER')")
    public VueCategorie activer(@PathVariable Long id) {
        return categories.changerStatut(id, "ACTIVE");
    }

    /**
     * Desactiver, jamais supprimer.
     *
     * <p>Des produits pointent vers cette categorie. La supprimer les
     * laisserait orphelins — ou ferait echouer la suppression sur une cle
     * etrangere, ce qui revient au meme pour l'utilisateur.</p>
     */
    @DeleteMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('CATEGORIE_PRODUIT_GERER')")
    public VueCategorie desactiver(@PathVariable Long id) {
        return categories.changerStatut(id, "INACTIVE");
    }

    public record DemandeCategorie(
            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 150, message = "Le nom ne peut pas depasser 150 caracteres.")
            String nom,

            /* Nul = categorie racine. */
            Long parentId,

            @Min(value = 0, message = "L'ordre ne peut pas etre negatif.")
            @Max(value = 9999, message = "L'ordre est trop grand.")
            Integer ordre) {
    }
}
