package com.garah.api.surveillance.domaine;

import java.util.List;

/**
 * Le resultat d une evaluation, avec son explication.
 *
 * <p>La {@code version} n est pas decorative : un 78 calcule en v1.0 et un 78
 * en v1.2 ne veulent pas dire la meme chose. Sans elle, comparer deux scores
 * dans le temps n a AUCUN sens — et une decision de blocage prise sur cette
 * comparaison serait arbitraire.</p>
 */
public record EvaluationRisque(
        double score,
        NiveauRisque niveau,
        String version,
        List<SignalRisque> signaux) {

    /** Le score n a de sens que si on peut dire POURQUOI. */
    public boolean estExplicable() {
        return !signaux.isEmpty() || score == 0;
    }
}
