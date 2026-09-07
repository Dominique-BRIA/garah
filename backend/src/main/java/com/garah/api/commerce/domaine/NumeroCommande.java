package com.garah.api.commerce.domaine;

/**
 * Le numero d une commande, et rien d autre.
 *
 * <p>Une projection minuscule, pour les listes qui doivent afficher « quelle
 * commande ? » sans charger la commande entiere avec toutes ses lignes.</p>
 */
public record NumeroCommande(Long id, String numero) {
}
