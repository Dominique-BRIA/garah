package com.garah.api.finance.domaine;

/**
 * La piece justificative d une ecriture.
 *
 * <p>Chaque ligne du grand livre pointe vers ce qui l a produite. C est ce qui
 * permet de repondre a « pourquoi doit-on 1 250 000 FCFA au marchand ABC ? »
 * en detaillant, ligne par ligne (correction A3).</p>
 *
 * <p>Comme ailleurs, l identifiant est OPAQUE : pas de cle etrangere, donc pas
 * de cycle entre la finance et le commerce.</p>
 */
public enum OrigineEcriture {
    LIGNE_COMMANDE,
    LIGNE_RETOUR,
    REGLEMENT,
    MANUEL
}
