package com.garah.api.commerce.domaine;

import java.util.List;

/**
 * Ce que la fusion du panier local a réellement produit.
 *
 * <h2>Pourquoi la réponse ne se contente pas du panier</h2>
 *
 * <p>🎯 Le panier local n'est <b>jamais</b> la vérité : il ne connaît ni le
 * stock, ni le catalogue, ni ce que le client a déjà mis de côté depuis un
 * autre appareil. La fusion produit donc presque toujours un panier différent
 * de celui que le visiteur avait sous les yeux.</p>
 *
 * <p>Rendre seulement le panier obligerait l'écran à comparer avant/après pour
 * deviner ce qui a changé — et un panier qui se modifie tout seul entre deux
 * pages, juste avant de payer, est la meilleure façon de perdre la
 * confiance.</p>
 *
 * @param ecarts ce qui n'est pas passé tel quel. <b>Vide</b> quand tout a été
 *               repris à l'identique : l'écran n'a alors rien à dire, et ne
 *               doit rien afficher.
 */
public record ResultatFusion(
        ContenuPanier panier,
        List<Ecart> ecarts) {

    /**
     * Ce qui est arrivé à une ligne du panier local.
     *
     * @param quantiteDemandee ce que le visiteur avait choisi hors connexion.
     * @param quantiteRetenue  ce que le panier porte maintenant. Zéro quand la
     *                         ligne a été refusée.
     */
    public record Ecart(
            Long varianteId,
            String designation,
            int quantiteDemandee,
            int quantiteRetenue,
            Nature nature) {
    }

    public enum Nature {

        /**
         * L'article n'est plus proposé à la vente : la ligne est refusée.
         *
         * <p>Le seul refus possible. On ne vérifie <b>pas</b> le stock ici —
         * voir {@code ServicePanier.fusionner} : la fusion n'est pas plus
         * stricte qu'un ajout ordinaire.</p>
         */
        INDISPONIBLE,

        /**
         * Le panier du serveur en contenait <b>déjà davantage</b>, et on garde
         * sa quantité.
         *
         * <p>C'est le cas du client qui a rempli un panier sur son ordinateur
         * puis en ajoute depuis son téléphone. Descendre à la quantité du
         * téléphone effacerait un choix qu'il n'a pas repris.</p>
         */
        DEJA_PLUS_GRANDE,

        /** La ligne existait avec moins : on monte à la quantité locale. */
        RELEVEE
    }
}
