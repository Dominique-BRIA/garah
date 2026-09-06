package com.garah.api.serviceclient.domaine;

/**
 * Le cycle de vie d une proposition de prix.
 *
 * <pre>
 *   PROPOSEE ──▶ ACCEPTEE ──utilisee dans une commande──▶ CONSOMMEE
 *       ├──▶ REFUSEE       (refus, ou contre-proposition)
 *       └──▶ EXPIREE       (travail periodique)
 * </pre>
 */
public enum StatutProposition {
    PROPOSEE,
    ACCEPTEE,
    REFUSEE,
    EXPIREE,
    CONSOMMEE
}
