package com.garah.api.mesure.domaine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Ce que le catalogue a produit sur une période.
 *
 * <h2>Lu dans les agrégats, jamais recalculé depuis le détail</h2>
 *
 * <p>🎯 {@code statistique_produit_jour} est rempli chaque nuit (D-15), et le
 * détail des vues est <b>purgé</b> derrière. Recalculer un bilan depuis
 * {@code vue_produit} donnerait donc des chiffres justes sur les dernières
 * semaines et faux au-delà — le pire des deux mondes, parce que rien ne
 * signalerait la bascule.</p>
 *
 * <p>C'est aussi pourquoi ces tables existent depuis la v1, avant le moindre
 * écran : <b>ces chiffres ne se calculent pas rétroactivement</b>. Une vue non
 * enregistrée en mars est perdue pour toujours.</p>
 *
 * @param jours      le nombre de jours <b>couverts par des agrégats</b>, pas la
 *                   longueur de la période demandée. S'ils diffèrent, une nuit
 *                   d'agrégation a été manquée — et c'est exactement ce que
 *                   l'écran doit pouvoir dire au lieu d'afficher un creux
 *                   inexpliqué dans la courbe.
 * @param meilleurs  les produits qui ont le plus rapporté sur la période.
 */
public record BilanPeriode(
        LocalDate du,
        LocalDate au,
        int jours,
        long vues,
        long vuesUniques,
        /** Les commandes PAYÉES sur la période, au jour de leur paiement. */
        long commandes,
        /** Les commandes annulées sur la période, au jour de leur annulation. */
        long commandesAnnulees,
        long quantiteVendue,
        /**
         * L'argent réellement ENCAISSÉ, frais d'acheminement compris.
         *
         * <p>⚠️ Ce n'est plus la somme des commandes créées : impayées,
         * annulées et expirées la gonflaient.</p>
         */
        BigDecimal chiffreAffaires,
        /** L'argent rendu, au jour où il est sorti. Jamais soustrait en silence. */
        BigDecimal montantRembourse,
        long retours,
        List<LigneBilan> meilleurs,
        List<PointJour> parJour) {

    /**
     * Un produit sur la période.
     *
     * @param tauxConversion part des vues qui ont abouti à une commande.
     *                       {@code null} si le produit n'a reçu <b>aucune</b>
     *                       vue : un taux calculé sur zéro vue vaudrait soit
     *                       une division par zéro, soit un « 0 % » qui
     *                       accuserait à tort une fiche que personne n'a
     *                       ouverte.
     */
    public record LigneBilan(
            Long produitId,
            String nom,
            long vues,
            long commandes,
            long quantiteVendue,
            BigDecimal chiffreAffaires,
            Double tauxConversion) {
    }

    /** Un jour de la période — de quoi tracer une courbe sans trou. */
    public record PointJour(
            LocalDate jour,
            long vues,
            long commandes,
            BigDecimal chiffreAffaires) {
    }
}
