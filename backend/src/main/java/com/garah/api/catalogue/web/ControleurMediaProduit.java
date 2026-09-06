package com.garah.api.catalogue.web;

import com.garah.api.catalogue.domaine.ResumeMedia;
import com.garah.api.catalogue.domaine.ServiceMediaProduit;
import com.garah.api.commun.erreur.RegleMetierViolee;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Les photos et vidéos d'un produit.
 *
 * <p>Sans ces routes, aucun produit ne peut recevoir d'image : le catalogue
 * s'affiche, mais vide de toute illustration — et l'invariant I-12 (« au moins
 * une photo pour publier ») rend alors la publication elle-même impossible.</p>
 */
@RestController
@RequestMapping("/api/produits/{produitId}/medias")
public class ControleurMediaProduit {

    private final ServiceMediaProduit medias;

    public ControleurMediaProduit(ServiceMediaProduit medias) {
        this.medias = medias;
    }

    /**
     * Téléverse une photo ou une vidéo.
     *
     * <p>{@code multipart/form-data}, pas du JSON en base64 : l'encodage
     * base64 gonfle le fichier d'un tiers, et une photo de 6 Mo deviendrait un
     * corps JSON de 8 Mo entièrement chargé en mémoire. Sur une instance
     * Render gratuite, quelques téléversements simultanés suffiraient à la
     * faire tomber.</p>
     *
     * <p>La permission distingue photo et vidéo côté référentiel
     * ({@code PRODUIT_AJOUTER_PHOTO}, {@code PRODUIT_AJOUTER_VIDEO}), mais le
     * type réel n'est connu qu'après lecture du contenu — donc après le
     * contrôle d'accès. On exige donc les deux : c'est le choix prudent, et il
     * évite qu'un droit « photo » ouvre le téléversement de vidéos.</p>
     */
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUIT_AJOUTER_PHOTO') and hasAuthority('PRODUIT_AJOUTER_VIDEO')")
    public ResumeMedia televerser(@PathVariable Long produitId,
                                  @RequestParam("fichier") MultipartFile fichier,
                                  @RequestParam(defaultValue = "false") boolean principal) {

        if (fichier == null || fichier.isEmpty()) {
            throw new RegleMetierViolee("FICHIER_MANQUANT", "Aucun fichier n'a été envoyé.");
        }

        try (var flux = fichier.getInputStream()) {
            return medias.televerser(produitId, fichier.getContentType(),
                    fichier.getSize(), flux, principal);
        } catch (IOException e) {
            throw new RegleMetierViolee("FICHIER_ILLISIBLE",
                    "Le fichier n'a pas pu être lu.");
        }
    }

    /**
     * La liste des médias, avec leurs URL publiques.
     *
     * <p>Réservée au back-office : la vitrine reçoit déjà les médias dans la
     * fiche produit. Une route de plus, publique, ne ferait que dupliquer une
     * information — et il faudrait penser à la fermer le jour où un média
     * devient confidentiel.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PRODUIT_CONSULTER')")
    public List<ResumeMedia> lister(@PathVariable Long produitId) {
        return medias.lister(produitId);
    }

    /**
     * Supprime un média.
     *
     * <p>{@code produitId} figure dans le chemin pour la lisibilité de l'API,
     * mais c'est bien {@code mediaId} qui identifie la ligne — un média
     * n'appartient qu'à un seul produit.</p>
     */
    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PRODUIT_SUPPRIMER_PHOTO')")
    public void supprimer(@PathVariable Long produitId, @PathVariable Long mediaId) {
        medias.supprimer(mediaId);
    }
}
