package com.garah.api.surveillance.domaine;

/**
 * Le niveau de risque, deduit du score.
 *
 * <p>Les bornes sont ecrites ICI et dans la contrainte
 * {@code score_risque_niveau_coherent}. Les deux doivent dire la meme chose :
 * un niveau qui contredit son score rendrait tous les tableaux de bord faux.</p>
 */
public enum NiveauRisque {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static NiveauRisque pour(double score) {
        if (score >= 80) return CRITICAL;
        if (score >= 50) return HIGH;
        if (score >= 25) return MEDIUM;
        return LOW;
    }
}
