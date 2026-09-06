package com.garah.api.sav.domaine;

/**
 * Cycle de vie d un retour.
 *
 * <pre>
 *   DEMANDE ──▶ ACCEPTE ──▶ RECEPTIONNE ──▶ VALIDE ──▶ CLOTURE
 *      │                          │
 *      └──▶ REFUSE                └── controle physique des articles
 * </pre>
 *
 * <p>Le remboursement n est declenche qu a VALIDE, donc APRES que quelqu un a
 * vu et controle la marchandise. Jamais a DEMANDE.</p>
 */
public enum StatutRetour {
    DEMANDE,
    ACCEPTE,
    REFUSE,
    RECEPTIONNE,
    VALIDE,
    CLOTURE
}
