package com.garah.api.logistique.domaine;

/** L etat d une expedition, projete depuis ses colis. */
public enum StatutExpedition {
    CREEE,
    PREPAREE,
    EN_TRANSIT,
    BLOQUEE,
    DISPONIBLE,
    REMISE,
    ANNULEE
}
