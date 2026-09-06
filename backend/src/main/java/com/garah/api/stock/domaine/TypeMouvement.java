package com.garah.api.stock.domaine;

/**
 * La nature d'un mouvement de stock.
 *
 * <p>Correspond à la contrainte {@code mouvement_stock_type_valide}.</p>
 */
public enum TypeMouvement {
    /** Réception de marchandise : le stock physique augmente. */
    ENTREE,
    /** La marchandise quitte l'entrepôt, après paiement confirmé. */
    SORTIE,
    /** Engagement par une commande en attente de paiement. */
    RESERVATION,
    /** Annulation d'un engagement : la marchandise redevient vendable. */
    LIBERATION,
    /** Retour client d'un article en bon état. */
    RETOUR,
    /** Article devenu invendable. */
    CASSE,
    /** Correction manuelle après inventaire. */
    AJUSTEMENT
}
