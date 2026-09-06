package com.garah.api.stock.domaine;

/**
 * Les trois compteurs d'un stock.
 *
 * <p>Un mouvement décrit <b>un seul</b> compteur (migration V16). Une
 * réservation, qui en fait bouger deux, produit donc deux lignes — comme une
 * écriture comptable en partie double.</p>
 */
public enum CompteurStock {
    /** Vendable immédiatement. */
    DISPONIBLE,
    /** Engagé par une commande en attente de paiement. */
    RESERVEE,
    /** Physiquement présent mais invendable. */
    ENDOMMAGEE
}
