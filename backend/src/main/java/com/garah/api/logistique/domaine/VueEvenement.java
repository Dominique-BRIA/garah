package com.garah.api.logistique.domaine;

import java.time.Instant;

/**
 * Une étape du parcours d'un colis.
 *
 * <p>C'est ce que le client voit dans son suivi. {@code responsableId} y figure
 * pour le back-office ; la route publique de suivi ne doit pas l'exposer —
 * savoir <b>qui</b> a scanné un colis ne regarde pas le destinataire.</p>
 */
public record VueEvenement(
        Long id,
        Long colisId,
        Long lieuId,
        Long responsableId,
        String type,
        String observation,
        Instant dateHeure) {

    public static VueEvenement de(EvenementExpedition e) {
        return new VueEvenement(e.getId(), e.getColisId(), e.getLieuId(),
                e.getResponsableId(), e.getType().name(), e.getObservation(),
                e.getDateHeure());
    }

    /** La même étape, sans l'identité de l'agent : pour le suivi client. */
    public static VueEvenement publique(EvenementExpedition e) {
        return new VueEvenement(e.getId(), e.getColisId(), e.getLieuId(),
                null, e.getType().name(), e.getObservation(), e.getDateHeure());
    }
}
