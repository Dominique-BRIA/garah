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
 * @param quantiteMax {@code null} signifie « et au-delà »
 * @param prixUnitaire toujours <b>TTC</b> (D-11)
 */
public record PalierPrix(
        int quantiteMin,
        Integer quantiteMax,
        BigDecimal prixUnitaire,
        String devise) {

    public static PalierPrix de(Tarification t) {
        return new PalierPrix(t.getQuantiteMin(), t.getQuantiteMax(),
                t.getPrixUnitaire(), t.getDevise());
    }

    /** Libellé lisible, utile en back-office et dans les messages d'erreur. */
    public String libelle() {
        return quantiteMax == null
                ? quantiteMin + " et +"
                : quantiteMin + " à " + quantiteMax;
    }
}
