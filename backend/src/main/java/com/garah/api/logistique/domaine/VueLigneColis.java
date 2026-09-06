package com.garah.api.logistique.domaine;

/**
 * Une ligne de colis : « telle ligne de commande, en telle quantité ».
 *
 * <p>{@code colisId} est passé en paramètre, jamais lu via
 * {@code ligne.getColis()} : cette relation est paresseuse, et la suivre hors
 * transaction lèverait un {@code LazyInitializationException}.</p>
 */
public record VueLigneColis(Long id, Long colisId, Long ligneCommandeId, int quantite) {

    public static VueLigneColis de(LigneColis ligne, Long colisId) {
        return new VueLigneColis(ligne.getId(), colisId,
                ligne.getLigneCommandeId(), ligne.getQuantite());
    }
}
