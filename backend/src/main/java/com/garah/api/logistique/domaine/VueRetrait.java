package com.garah.api.logistique.domaine;

import java.time.Instant;

/**
 * Un retrait de marchandise en point de récupération.
 *
 * <p>⚠️ {@code codeRetrait} est un <b>secret partagé</b> : le présenter suffit
 * à repartir avec la marchandise. Il n'a donc rien à faire dans une liste ni
 * dans un journal — seulement dans la réponse adressée au client propriétaire
 * de la commande.</p>
 */
public record VueRetrait(
        Long id,
        Long expeditionId,
        Long clientId,
        String codeRetrait,
        String statut,
        Instant dateRetrait) {

    public static VueRetrait de(RetraitMarchandise r) {
        return new VueRetrait(r.getId(), r.getExpeditionId(), r.getClientId(),
                r.getCodeRetrait(), r.getStatut(), r.getDateRetrait());
    }

    /** Sans le code : pour tout ce qui n'est pas destiné au client lui-même. */
    public static VueRetrait sansCode(RetraitMarchandise r) {
        return new VueRetrait(r.getId(), r.getExpeditionId(), r.getClientId(),
                null, r.getStatut(), r.getDateRetrait());
    }
}
