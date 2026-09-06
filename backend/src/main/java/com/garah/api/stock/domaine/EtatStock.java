package com.garah.api.stock.domaine;

/**
 * L'état d'un stock, tel qu'on l'affiche.
 *
 * @param total physiquement présent : disponible + réservé + endommagé
 */
public record EtatStock(
        Long varianteId,
        int disponible,
        int reserve,
        int endommage,
        int total,
        boolean sousLeSeuil) {

    public static EtatStock de(Stock s) {
        return new EtatStock(
                s.getVarianteId(),
                s.getQuantiteDisponible(),
                s.getQuantiteReservee(),
                s.getQuantiteEndommagee(),
                s.getQuantiteDisponible() + s.getQuantiteReservee() + s.getQuantiteEndommagee(),
                s.sousLeSeuil());
    }
}
