package com.garah.api.catalogue.domaine;

/**
 * Le cycle de vie d'une fiche produit.
 *
 * <pre>
 *   BROUILLON ──publier──▶ PUBLIE ──masquer──▶ MASQUE ──publier──▶ PUBLIE
 *       │                    │                    │
 *       └────────────────────┴────────────────────┴──▶ ARCHIVE  (terminal)
 * </pre>
 *
 * <p>ARCHIVE est terminal et volontairement irréversible : un produit vendu
 * une fois ne doit jamais disparaître, parce que des lignes de commande le
 * référencent. On l'archive, on ne le supprime pas.</p>
 */
public enum StatutProduit {
    /** En cours de saisie. Invisible du public. */
    BROUILLON,
    /** Visible et achetable. */
    PUBLIE,
    /** Retiré temporairement de la vente, la fiche reste modifiable. */
    MASQUE,
    /** Fin de vie. Conservé pour l'historique des commandes. */
    ARCHIVE
}
