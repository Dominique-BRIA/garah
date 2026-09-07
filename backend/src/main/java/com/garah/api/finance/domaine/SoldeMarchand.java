package com.garah.api.finance.domaine;

import java.math.BigDecimal;

/**
 * Ce qu'on doit à un marchand, tel qu'une liste l'affiche.
 *
 * <h2>Le solde est une SOMME, jamais un total stocké</h2>
 *
 * <p>Il est recalculé à chaque demande depuis les écritures. Un total stocké
 * finit toujours par mentir : il suffit d'une écriture ajoutée sans passer par
 * le code qui l'entretient, et plus rien ne le signale.</p>
 *
 * @param solde ce qui reste dû. <b>Zéro pour un marchand sans aucune
 *              écriture</b> — il n'a rien vendu, ce n'est pas une donnée
 *              manquante. L'écran doit écrire « 0 », pas un tiret.
 */
public record SoldeMarchand(
        Long marchandId,
        String code,
        String nom,
        String statut,
        BigDecimal solde,
        String devise) {
}
