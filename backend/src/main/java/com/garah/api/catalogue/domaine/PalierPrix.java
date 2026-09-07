package com.garah.api.catalogue.domaine;

import java.math.BigDecimal;

/**
 * Une ligne de la grille tarifaire, telle qu'on l'affiche au client.
 *
 * <pre>
 * 1 à 4      15 000 FCFA
 * 5 à 9      13 000 FCFA
 * 10 et +    11 500 FCFA
 * </pre>
 *
 * @param id l'identifiant de la ligne de tarification. Il est exposé parce que
 *           le back-office doit pouvoir <b>désigner</b> un palier pour en
 *           changer le prix ou le retirer. Sans lui, la seule façon de
 *           corriger une erreur de saisie serait de retaper toute la grille.
 * @param quantiteMax {@code null} signifie « et au-delà »
 * @param prixUnitaire toujours <b>TTC</b> (D-11)
 */
public record PalierPrix(
        Long id,
        int quantiteMin,
        Integer quantiteMax,
        BigDecimal prixUnitaire,
        String devise) {

    public static PalierPrix de(Tarification t) {
        return new PalierPrix(t.getId(), t.getQuantiteMin(), t.getQuantiteMax(),
                t.getPrixUnitaire(), t.getDevise());
    }

    /** Libellé lisible, utile en back-office et dans les messages d'erreur. */
    public String libelle() {
        return quantiteMax == null
                ? quantiteMin + " et +"
                : quantiteMin + " à " + quantiteMax;
    }
}
