package com.garah.api.stock.domaine;

/**
 * Pourquoi un mouvement a eu lieu (correction A5).
 *
 * <p>Sans cette information, un écart de stock est indébrouillable : on voit
 * que 12 unités ont disparu, sans savoir si c'est une vente, une casse ou une
 * erreur de saisie.</p>
 *
 * <p>⚠️ {@code origine_id} est un identifiant <b>opaque</b>, sans clé
 * étrangère. C'est volontaire : c'est ce qui empêche le domaine stock de
 * dépendre du domaine commerce, et donc de créer un cycle (chapitre 06 §4.3).</p>
 */
public enum OrigineMouvement {
    COMMANDE,
    RETOUR,
    EXPEDITION,
    INVENTAIRE,
    MANUEL
}
