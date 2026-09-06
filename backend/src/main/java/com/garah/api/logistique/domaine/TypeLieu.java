package com.garah.api.logistique.domaine;

/**
 * Les trois natures de lieu (correction A10).
 *
 * <p>Une seule table {@code lieu} les porte toutes. Le modèle initial avait
 * deux tables séparées — et ne pouvait donc enregistrer ni le départ de
 * l'entrepôt, ni la remise au client.</p>
 */
public enum TypeLieu {
    ENTREPOT,
    POINT_TRANSIT,
    POINT_RECUPERATION
}
