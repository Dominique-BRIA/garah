package com.garah.api.catalogue.domaine;

import java.math.BigDecimal;
import java.util.List;

/**
 * Une fiche produit telle que la <b>vitrine</b> l'affiche.
 *
 * <h2>Pourquoi une vue à part, et pas {@link DetailProduit} enrichi</h2>
 *
 * <p>🎯 « Deux publics, deux routes » — la règle fondatrice n°4 du projet. Le
 * back-office et la vitrine ne posent pas la même question sur un produit :
 * l'un demande « où en est cette fiche ? », l'autre « qu'est-ce que ça coûte
 * et puis-je l'acheter ? ».</p>
 *
 * <p>Ajouter les paliers et le stock à {@code DetailProduit} obligerait la
 * fiche d'administration à les charger aussi — deux requêtes de plus à chaque
 * ouverture d'un brouillon, pour des chiffres que cet écran-là n'affiche pas.
 * Et l'inverse est vrai : le statut et la référence interne n'ont rien à faire
 * en vitrine.</p>
 *
 * <h2>Le prix affiché n'engage à rien</h2>
 *
 * <p>⚠️ Ces paliers sont ceux <b>du jour</b>. Le tarif qui compte est figé par
 * le serveur au passage de commande (D-10) : la vitrine informe, elle ne
 * promet pas. Un écran qui présenterait son propre calcul comme un engagement
 * mentirait le jour où un tarif change entre l'affichage et le paiement.</p>
 *
 * @param marchandNom qui vend. Sur une place de marché, le savoir fait partie
 *                    de la décision d'achat — et GARAH revend pour des tiers.
 */
public record FicheVitrine(
        Long id,
        String nom,
        String slug,
        String description,
        BigDecimal tauxTva,
        Long marchandId,
        String marchandNom,
        Categorie categorie,
        List<Declinaison> declinaisons,
        List<Media> medias) {

    public record Categorie(Long id, String nom, String slug) {
    }

    /**
     * Une déclinaison achetable, avec sa grille et sa disponibilité.
     *
     * @param paliers    la grille de prix, du plus petit palier au plus grand.
     *                   <b>Vide</b> quand aucun tarif n'est paramétré : la
     *                   déclinaison n'est alors pas achetable, et l'écran doit
     *                   le dire plutôt qu'afficher « 0 F ».
     * @param disponible la quantité vendable. Zéro quand le stock n'existe pas
     *                   encore — ce qui, depuis I-15, ne devrait plus arriver.
     * @param quantiteMinimale le plus petit palier de la grille : le MOQ
     *                   d'Alibaba. Vaut presque toujours 1 chez GARAH ;
     *                   l'écran ne l'affiche que s'il dépasse 1, sinon c'est
     *                   du bruit sur toutes les fiches.
     */
    public record Declinaison(
            Long id,
            String sku,
            String libelle,
            boolean parDefaut,
            List<PalierPrix> paliers,
            int disponible,
            int quantiteMinimale) {

        /**
         * Achetable : il faut <b>un prix et du stock</b>.
         *
         * <p>Les deux, et pas seulement le stock : une déclinaison en rayon
         * mais sans tarif se laisserait mettre au panier et échouerait au
         * paiement — c'est-à-dire au pire moment, quand le client a déjà sorti
         * son téléphone.</p>
         */
        public boolean achetable() {
            return !paliers.isEmpty() && disponible > 0;
        }
    }

    public record Media(Long id, String type, String url, boolean principal, int ordre) {
    }
}
