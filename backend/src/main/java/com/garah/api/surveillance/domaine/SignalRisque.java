package com.garah.api.surveillance.domaine;

/**
 * Un signal qui contribue au score.
 *
 * <p>C est la piece manquante de la specification (correction A14) : elle
 * exigeait « le score doit etre explicable » mais ne stockait qu un nombre.</p>
 *
 * <p>L ecran d administration n affiche pas « 78 ». Il affiche les phrases.</p>
 *
 * @param code    identifiant stable, sert de cle de traduction
 * @param libelle phrase lisible : « 7 echecs de connexion en 24 h »
 * @param valeur  la mesure brute, pour pouvoir recalculer
 * @param poids   la contribution au score
 */
public record SignalRisque(String code, String libelle, double valeur, double poids) {
}
