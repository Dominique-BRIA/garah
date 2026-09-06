package com.garah.api.surveillance.domaine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un score de risque calculé et archivé.
 *
 * <p>{@code versionAlgorithme} n'est pas décoratif : le jour où la formule
 * change, il permet de savoir qu'un score de mars n'est pas comparable à un
 * score de septembre. Sans lui, on comparerait des chiffres qui ne mesurent
 * plus la même chose.</p>
 */
public record VueScoreRisque(
        Long id,
        Long clientId,
        BigDecimal score,
        String niveau,
        String versionAlgorithme,
        String details,
        Instant dateCalcul) {

    public static VueScoreRisque de(ScoreRisqueClient s) {
        return new VueScoreRisque(s.getId(), s.getClientId(), s.getScore(),
                s.getNiveau().name(), s.getVersionAlgorithme(), s.getDetails(),
                s.getDateCalcul());
    }
}
