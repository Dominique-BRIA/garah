package com.garah.api.commerce.domaine;

import java.math.BigDecimal;
import java.util.List;

/**
 * Le panier tel qu'on l'affiche.
 *
 * <p>⚠️ Les prix sont ceux d'<b>aujourd'hui</b>, recalculés à chaque
 * affichage. Un article mis au panier il y a trois semaines s'affiche au tarif
 * courant, pas à celui de l'époque : un panier n'engage personne.</p>
 *
 * @param indisponibles lignes dont le stock ne suffit plus — on les signale
 *                      AVANT le paiement, pas pendant
 */
public record ContenuPanier(
        Long panierId,
        List<Ligne> lignes,
        BigDecimal montantArticles,
        int nombreArticles,
        List<String> indisponibles) {

    public record Ligne(
            Long varianteId,
            String designation,
            int quantite,
            BigDecimal prixUnitaire,
            BigDecimal montantLigne,
            int disponible,
            boolean vendable,
            /*
             * Vrai si le prix vient d'une negociation acceptee, et non du
             * tarif. Dit a l'ecran POURQUOI le prix differe de la fiche
             * produit — sans quoi le client croirait a une erreur.
             */
            boolean prixNegocie) {
    }

    public static ContenuPanier vide() {
        return new ContenuPanier(null, List.of(), BigDecimal.ZERO, 0, List.of());
    }
}
