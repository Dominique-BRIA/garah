package com.garah.api.surveillance.domaine;

/**
 * Les faits d'authentification et d'anomalie surveillés (spec §18).
 *
 * <p>Ces valeurs correspondent exactement à la contrainte
 * {@code evenement_securite_type_valide} du schéma.</p>
 *
 * <p>⚠️ Ne pas confondre avec les deux autres journaux (chapitre 01 §6) :
 * {@code activite_client} raconte le parcours du client, {@code audit_log}
 * raconte qui a modifié quoi en interne. Ici, il ne s'agit que de sécurité.</p>
 */
public enum TypeEvenementSecurite {
    CONNEXION_REUSSIE,
    ECHEC_CONNEXION,
    NOUVEL_APPAREIL,
    CHANGEMENT_MOT_DE_PASSE,
    CHANGEMENT_EMAIL,
    CHANGEMENT_TELEPHONE,
    ECHEC_PAIEMENT,
    ACTIVITE_INHABITUELLE,
    BLOCAGE_COMPTE
}
