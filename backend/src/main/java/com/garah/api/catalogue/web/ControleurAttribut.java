package com.garah.api.catalogue.web;

import com.garah.api.catalogue.domaine.ServiceAttribut;
import com.garah.api.catalogue.domaine.VueAttribut;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Les dimensions de declinaison : Taille, Couleur, Conditionnement.
 *
 * <p>C'est le <i>variation theme</i> d'Amazon. Le referentiel est PARTAGE par
 * tout le catalogue : « Taille » est defini une fois et reutilise par tous les
 * vetements.</p>
 *
 * <p>⚠️ La lecture est ouverte a qui peut consulter un produit, l'ecriture
 * demande {@code ATTRIBUT_GERER}. Ce n'est pas la meme chose : composer une
 * declinaison suppose de LIRE les dimensions ; en inventer une nouvelle touche
 * un referentiel que tout le catalogue partage, et qu'on ne renomme pas a la
 * legere.</p>
 */
@RestController
@RequestMapping("/api/attributs")
public class ControleurAttribut {

    private final ServiceAttribut attributs;

    public ControleurAttribut(ServiceAttribut attributs) {
        this.attributs = attributs;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PRODUIT_CONSULTER')")
    public List<VueAttribut> lister() {
        return attributs.lister();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUIT_CONSULTER')")
    public VueAttribut detail(@PathVariable Long id) {
        return attributs.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ATTRIBUT_GERER')")
    public VueAttribut creer(@Valid @RequestBody DemandeAttribut demande) {
        return attributs.creer(demande.nom(), demande.typeAffichage());
    }

    @PostMapping("/{id}/valeurs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ATTRIBUT_GERER')")
    public VueAttribut ajouterValeur(@PathVariable Long id,
                                     @Valid @RequestBody DemandeValeur demande) {
        return attributs.ajouterValeur(id, demande.libelle(),
                demande.valeurAffichage(), demande.ordre());
    }

    /**
     * Retire une valeur du referentiel.
     *
     * <p>Refuse par un {@code 409} si des declinaisons la portent, en disant
     * combien. La cle etrangere refuserait de toute facon, mais avec un
     * message d'integrite qui ne dit ni combien ni lesquelles.</p>
     */
    @DeleteMapping("/{id}/valeurs/{valeurId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ATTRIBUT_GERER')")
    public void supprimerValeur(@PathVariable Long id, @PathVariable Long valeurId) {
        attributs.supprimerValeur(id, valeurId);
    }

    // -------------------------------------------------------------------------

    public record DemandeAttribut(
            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 100, message = "Le nom ne peut pas depasser 100 caracteres.")
            String nom,

            /* LISTE ou PASTILLE. La base n'en accepte pas d'autres (V4). */
            @Pattern(regexp = "^(LISTE|PASTILLE)$",
                     message = "Une dimension s'affiche en liste ou en pastille de couleur.")
            String typeAffichage) {
    }

    public record DemandeValeur(
            @NotBlank(message = "Le libelle est obligatoire.")
            @Size(max = 100, message = "Le libelle ne peut pas depasser 100 caracteres.")
            String libelle,

            /*
             * La couleur d'une pastille, en hexadecimal. Ignoree sur une
             * dimension affichee en liste : elle n'y voudrait rien dire.
             */
            @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$",
                     message = "La couleur s'ecrit en hexadecimal, par exemple #1E88E5.")
            String valeurAffichage,

            /* Nul = a la fin. 38 avant 39 avant 40, jamais dans l'ordre alphabetique. */
            @Min(value = 0, message = "L'ordre ne peut pas etre negatif.")
            Integer ordre) {
    }
}
