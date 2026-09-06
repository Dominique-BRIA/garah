package com.garah.api.commerce.domaine;

/**
 * Le cycle de vie d un paiement.
 *
 * <pre>
 * INITIE ──▶ EN_ATTENTE ──▶ CONFIRME     (webhook de l operateur)
 *    │            │
 *    └────────────┴──────▶ ECHOUE
 *                 └──────▶ ANNULE        (delai depasse)
 * </pre>
 */
public enum StatutPaiement {
    INITIE,
    EN_ATTENTE,
    CONFIRME,
    ECHOUE,
    ANNULE
}
