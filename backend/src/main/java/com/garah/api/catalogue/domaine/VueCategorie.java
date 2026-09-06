package com.garah.api.catalogue.domaine;

import java.util.List;

/**
 * Une categorie, avec ses enfants.
 *
 * <p>L'arbre est renvoye D'UN COUP plutot qu'une requete par niveau. Un
 * catalogue a rarement plus de trois niveaux et quelques dizaines de
 * categories : charger le tout coute une requete, alors qu'un chargement
 * paresseux en couterait une par branche ouverte — le probleme N+1, cote
 * reseau cette fois.</p>
 */
public record VueCategorie(
        Long id,
        Long parentId,
        String nom,
        String slug,
        int ordre,
        String statut,
        List<VueCategorie> enfants) {

    public static VueCategorie de(CategorieProduit c, List<VueCategorie> enfants) {
        return new VueCategorie(
                c.getId(),
                c.getParent() == null ? null : c.getParent().getId(),
                c.getNom(), c.getSlug(), c.getOrdre(), c.getStatut(), enfants);
    }

    /** Sans ses enfants — pour une liste plate ou une liste deroulante. */
    public static VueCategorie plate(CategorieProduit c) {
        return de(c, List.of());
    }
}
