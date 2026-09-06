package com.garah.api.surveillance.domaine;

import java.time.Instant;

/** Une alerte de sécurité, telle que la voit le back-office. */
public record VueAlerte(
        Long id,
        Long clientId,
        Long evenementSecuriteId,
        String type,
        String gravite,
        String statut,
        Long traitePar,
        String decision,
        Instant dateCreation,
        Instant dateTraitement) {

    public static VueAlerte de(AlerteSecurite a) {
        return new VueAlerte(a.getId(), a.getClientId(), a.getEvenementSecuriteId(),
                a.getType(), a.getGravite().name(), a.getStatut(), a.getTraitePar(),
                a.getDecision(), a.getDateCreation(), a.getDateTraitement());
    }
}
