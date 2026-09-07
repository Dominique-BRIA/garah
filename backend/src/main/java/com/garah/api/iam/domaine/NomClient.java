package com.garah.api.iam.domaine;

/**
 * De quoi désigner un client dans une liste, et rien de plus.
 *
 * <p>Une projection minuscule, pour les domaines qui affichent « de qui vient
 * cette commande » sans avoir à charger — ni pouvoir modifier — le client
 * entier.</p>
 *
 * <p>Le {@code code} est ce qu'on dicte au téléphone et ce qu'on lit sur un
 * bordereau ; le {@code nom} est ce qu'on reconnaît à l'écran. Les deux
 * servent, et pour des raisons différentes.</p>
 */
public record NomClient(Long id, String code, String nom, String email) {
}
