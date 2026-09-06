package com.garah.api.commerce.domaine;

/**
 * Sens d un mouvement d argent.
 *
 * <p>Le sens est porte par ce type, JAMAIS par un montant negatif : la
 * contrainte {@code paiement_montant_positif} l impose. Un montant signe
 * casserait toutes les sommes (correction A12).</p>
 */
public enum TypePaiement {
    ENCAISSEMENT,
    REMBOURSEMENT
}
