package com.garah.api.commerce.domaine;

/**
 * Les moyens acceptes en v1 (D-06).
 *
 * <p>Pas d especes : rien n est prepare ni achemine avant paiement. Cela
 * supprime la reconciliation de caisse dans chaque point de retrait, et le
 * risque d une marchandise acheminee jusqu a Bangui puis jamais retiree.</p>
 */
public enum MoyenPaiement {
    MTN_MOMO,
    ORANGE_MONEY,
    VIREMENT
}
