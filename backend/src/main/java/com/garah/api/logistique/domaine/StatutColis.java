package com.garah.api.logistique.domaine;

/**
 * L etat d un colis — une PROJECTION de ses evenements, jamais une saisie.
 *
 * <p>Il existe pour l affichage et pour les requetes de liste. Il doit rester
 * RECALCULABLE a tout moment : c est ce que verifie le test de projection.</p>
 */
public enum StatutColis {
    CREE,
    EN_TRANSIT,
    BLOQUE,
    DISPONIBLE,
    REMIS,
    PERDU
}
