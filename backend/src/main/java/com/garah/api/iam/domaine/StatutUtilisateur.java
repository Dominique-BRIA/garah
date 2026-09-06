package com.garah.api.iam.domaine;

/** États d'un compte. Correspond à la contrainte {@code utilisateur_statut_valide}. */
public enum StatutUtilisateur {
    ACTIF,
    INACTIF,
    BLOQUE
}
