package com.garah.api.commerce.domaine;

/**
 * Chez qui un paiement en ligne a été demandé (D-55).
 *
 * <p>⚠️ Se lit sur le {@link Paiement}, jamais sur le réglage courant. Le
 * réglage {@code GARAH_PAIEMENT_FOURNISSEUR} ne décide que des paiements à
 * venir : un paiement Campay resté en attente au moment de la bascule doit
 * continuer d'être interrogé chez Campay, sinon le client débité voit sa
 * commande annulée au bout du délai.</p>
 */
public enum FournisseurPaiement {
    CAMPAY,
    MONEYFUSION
}
