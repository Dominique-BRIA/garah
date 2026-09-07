package com.garah.api.marchand.domaine;

/**
 * Le nom d'un marchand, et rien d'autre.
 *
 * <p>Une projection minuscule, pour les listes d'un autre domaine qui doivent
 * afficher « de qui vient ce produit » sans avoir a charger — ni pouvoir
 * modifier — le marchand entier.</p>
 */
public record NomMarchand(Long id, String nom) {
}
