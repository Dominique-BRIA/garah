package com.garah.api.stock.domaine;

import com.garah.api.catalogue.domaine.DesignationVariante;

/**
 * L'état d'un stock, tel qu'on l'affiche.
 *
 * <p>La désignation vient du <b>catalogue</b>, pas du stock : celui-ci ne
 * connaît que des identifiants de variante. Un écran qui afficherait
 * « variante 42 : 7 disponibles » ne servirait à rien — personne ne sait ce
 * qu'est la variante 42.</p>
 *
 * @param total physiquement présent : disponible + réservé + endommagé
 * @param produitNom {@code null} quand la désignation n'a pas été résolue —
 *                   c'est le cas des routes qui répondent sur une seule
 *                   variante, où l'appelant sait déjà de quoi il parle
 */
public record EtatStock(
        Long varianteId,
        String sku,
        String libelle,
        Long produitId,
        String produitNom,
        int disponible,
        int reserve,
        int endommage,
        int total,
        int seuilAlerte,
        boolean sousLeSeuil) {

    public static EtatStock de(Stock s) {
        return de(s, null);
    }

    public static EtatStock de(Stock s, DesignationVariante designation) {
        return new EtatStock(
                s.getVarianteId(),
                designation == null ? null : designation.sku(),
                designation == null ? null : designation.libelle(),
                designation == null ? null : designation.produitId(),
                designation == null ? null : designation.produitNom(),
                s.getQuantiteDisponible(),
                s.getQuantiteReservee(),
                s.getQuantiteEndommagee(),
                s.getQuantiteDisponible() + s.getQuantiteReservee() + s.getQuantiteEndommagee(),
                s.getSeuilAlerte(),
                s.sousLeSeuil());
    }
}
