package com.garah.api.catalogue.domaine;

import java.math.BigDecimal;

/**
 * Le prix d'appel d'un produit : le plus bas de toute sa grille.
 *
 * <p>C'est ce qu'on affiche en liste — « à partir de 3 000 FCFA ». Le prix
 * exact dépend de la déclinaison choisie et de la quantité commandée, deux
 * choses qu'une vignette ne connaît pas encore.</p>
 *
 * <p>⚠️ Cette projection existe pour être calculée <b>en une seule requête
 * pour toute la page</b>. Interroger la grille produit par produit ferait
 * vingt-quatre allers-retours pour afficher vingt-quatre lignes : invisible
 * sur la machine du développeur, très visible sur une connexion mobile.</p>
 */
public record PrixMinProduit(Long produitId, BigDecimal prixMin, String devise) {
}
