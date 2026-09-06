package com.garah.api.catalogue.domaine;

import java.util.List;

/**
 * Une variante et sa grille tarifaire.
 *
 * <p>C'est ELLE qui se vend, pas le produit (D-01). Un sac de ciment sans
 * declinaison reelle a quand meme UNE variante, dite « par defaut » : un seul
 * chemin de code, toujours.</p>
 *
 * <p>{@link PalierPrix} est reutilise tel quel : c'etait deja un DTO, pas une
 * entite. En redefinir un second donnerait deux representations du meme prix,
 * qui finiraient par diverger.</p>
 */
public record VueVariante(
        Long id,
        Long produitId,
        String sku,
        String libelle,
        boolean parDefaut,
        String statut,
        List<PalierPrix> paliers) {

    public static VueVariante de(Variante v, Long produitId, List<PalierPrix> paliers) {
        return new VueVariante(v.getId(), produitId, v.getSku(), v.getLibelle(),
                v.estParDefaut(), v.getStatut(), paliers);
    }
}
