package com.garah.api.commerce.domaine;

/**
 * Le cycle de vie d'une commande (chapitre 04 §4.1).
 *
 * <pre>
 * EN_ATTENTE_PAIEMENT ──▶ PAYEE ──▶ EN_PREPARATION ──▶ PRETE
 *          │                │                              │
 *          ▼                ▼                              ▼
 *       ANNULEE          ANNULEE                       EXPEDIEE
 *   (client ou délai)  (Admin seul,                        │
 *                       remboursement)                     ▼
 *                                                     DISPONIBLE
 *                                                          │
 *                                                          ▼
 *                                                       RETIREE
 * </pre>
 *
 * <p>Le cycle est <b>linéaire</b> parce qu'il n'y a pas d'espèces (D-06) :
 * rien n'est préparé ni acheminé avant d'être payé.</p>
 */
public enum StatutCommande {
    EN_ATTENTE_PAIEMENT,
    PAYEE,
    EN_PREPARATION,
    PRETE,
    EXPEDIEE,
    DISPONIBLE,
    RETIREE,
    ANNULEE
}
