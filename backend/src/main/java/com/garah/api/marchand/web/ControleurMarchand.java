package com.garah.api.marchand.web;

import com.garah.api.marchand.domaine.ServiceMarchand;
import com.garah.api.marchand.domaine.StatutMarchand;
import com.garah.api.marchand.domaine.TypeMarchand;
import com.garah.api.marchand.domaine.VueMarchand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Les marchands.
 *
 * <p>Premier maillon du catalogue : un produit exige un marchand. Sans ces
 * routes, aucun produit ne pouvait etre cree depuis une interface.</p>
 */
@RestController
@RequestMapping("/api/marchands")
public class ControleurMarchand {

    private static final int TAILLE_MAX = 100;

    private final ServiceMarchand marchands;

    public ControleurMarchand(ServiceMarchand marchands) {
        this.marchands = marchands;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MARCHAND_CONSULTER')")
    public Page<VueMarchand> lister(@RequestParam(required = false) String recherche,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int taille) {
        return marchands.lister(recherche, PageRequest.of(
                Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX), Sort.by("nom")));
    }

    /**
     * Ceux qu'on peut associer a un nouveau produit : les ACTIFS seulement.
     *
     * <p>Route distincte de la liste complete, et c'est volontaire. Melanger
     * les deux derriere un {@code ?statut=} obligerait chaque appelant a
     * penser au filtre — et celui qui l'oublie proposerait des marchands
     * desactives dans un formulaire de creation.</p>
     */
    @GetMapping("/selectionnables")
    @PreAuthorize("hasAuthority('MARCHAND_CONSULTER')")
    public Page<VueMarchand> selectionnables(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "100") int taille) {
        return marchands.actifs(PageRequest.of(
                Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX), Sort.by("nom")));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MARCHAND_CONSULTER')")
    public VueMarchand detail(@PathVariable Long id) {
        return marchands.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MARCHAND_CREER')")
    public VueMarchand creer(@Valid @RequestBody DemandeMarchand demande) {
        return marchands.creer(demande.code(), demande.nom(), demande.type(),
                demande.telephone(), demande.email());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MARCHAND_MODIFIER')")
    public VueMarchand modifier(@PathVariable Long id,
                                @Valid @RequestBody DemandeModificationMarchand demande) {
        return marchands.modifier(id, demande.nom(), demande.telephone(), demande.email());
    }

    /**
     * Desactiver, jamais supprimer.
     *
     * <p>Les ventes passees portent l'identifiant du marchand, et le grand
     * livre lui doit peut-etre encore de l'argent. Une suppression laisserait
     * des lignes de commande orphelines et un solde impossible a regler.</p>
     */
    @DeleteMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('MARCHAND_DESACTIVER')")
    public VueMarchand desactiver(@PathVariable Long id) {
        return marchands.changerStatut(id, StatutMarchand.INACTIF);
    }

    @PostMapping("/{id}/activation")
    @PreAuthorize("hasAuthority('MARCHAND_ACTIVER')")
    public VueMarchand activer(@PathVariable Long id) {
        return marchands.changerStatut(id, StatutMarchand.ACTIF);
    }

    /** Ce qu'on envoie pour creer un marchand. */
    public record DemandeMarchand(
            @NotBlank(message = "Le code est obligatoire.")
            @Size(max = 20, message = "Le code ne peut pas depasser 20 caracteres.")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                     message = "Le code ne peut contenir que lettres, chiffres, points, tirets et underscores.")
            String code,

            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 150, message = "Le nom ne peut pas depasser 150 caracteres.")
            String nom,

            @NotNull(message = "Le type est obligatoire.")
            TypeMarchand type,

            @Size(max = 30, message = "Numero de telephone trop long.")
            @Pattern(regexp = "^$|^[+()0-9 .-]{6,30}$",
                     message = "Ce numero de telephone n'est pas valide.")
            String telephone,

            @Size(max = 255, message = "Adresse e-mail trop longue.")
            @Email(message = "Cette adresse e-mail n'est pas valide.")
            String email) {
    }

    /**
     * Ce qu'on envoie pour modifier.
     *
     * <p>Ni le code ni le type : le premier est une reference partagee avec le
     * partenaire, le second decide de la commission et donc du grand livre.
     * Les changer rendrait incoherentes des ecritures deja passees.</p>
     */
    public record DemandeModificationMarchand(
            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 150, message = "Le nom ne peut pas depasser 150 caracteres.")
            String nom,

            @Size(max = 30, message = "Numero de telephone trop long.")
            @Pattern(regexp = "^$|^[+()0-9 .-]{6,30}$",
                     message = "Ce numero de telephone n'est pas valide.")
            String telephone,

            @Size(max = 255, message = "Adresse e-mail trop longue.")
            @Email(message = "Cette adresse e-mail n'est pas valide.")
            String email) {
    }
}
