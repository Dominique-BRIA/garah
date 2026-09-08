package com.garah.api.stock.domaine;

import java.time.Instant;

/**
 * Un mouvement de stock, tel que l'historique l'affiche.
 *
 * <p>C'est ce qui répond à « pourquoi n'en reste-t-il que trois ? ». La
 * quantité courante est une <b>photo de l'instant</b> ; les mouvements sont
 * les faits datés qui l'expliquent — et eux ne s'effacent jamais.</p>
 *
 * <p>{@code quantiteAvant} et {@code quantiteApres} sont conservés tels quels,
 * pas recalculés : ils disent ce que le compteur valait <b>à ce moment-là</b>.
 * C'est ce qui permet de repérer un écart introduit par une écriture directe
 * en base — le mouvement suivant ne partirait pas de là où le précédent est
 * arrivé.</p>
 *
 * @param origineType d'où vient le mouvement : une commande, un retour, un
 *                    inventaire, ou un geste manuel
 */
public record VueMouvement(
        Long id,
        String type,
        String compteur,
        int quantite,
        int quantiteAvant,
        int quantiteApres,
        String origineType,
        Long origineId,
        Long auteurId,
        String commentaire,
        Instant dateOperation) {

    public static VueMouvement de(MouvementStock m) {
        return new VueMouvement(
                m.getId(),
                m.getType().name(),
                m.getCompteur().name(),
                m.getQuantite(),
                m.getQuantiteAvant(),
                m.getQuantiteApres(),
                m.getOrigineType() == null ? null : m.getOrigineType().name(),
                m.getOrigineId(),
                m.getAuteurId(),
                m.getCommentaire(),
                m.getDateOperation());
    }
}
