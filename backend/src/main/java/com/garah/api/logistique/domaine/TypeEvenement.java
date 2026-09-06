package com.garah.api.logistique.domaine;

/**
 * Les faits enregistrables sur le parcours d un colis.
 *
 * <p>C est la VERITE du parcours. Le statut du colis n en est qu une
 * projection (regle fondatrice n 1).</p>
 */
public enum TypeEvenement {
    /** Le colis quitte un lieu. */
    DEPART,
    /** Le colis arrive dans un lieu. */
    ARRIVEE,
    /** Prise en charge formelle par le responsable du lieu. */
    RECEPTION,
    /** Verification de l etat du colis. */
    CONTROLE,
    /** Probleme constate : casse, ouverture, retard. */
    ANOMALIE,
    /** Remise au client. Terminal. */
    REMISE
}
