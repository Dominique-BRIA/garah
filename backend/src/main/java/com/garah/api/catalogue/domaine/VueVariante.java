package com.garah.api.catalogue.domaine;

import java.util.Comparator;
import java.util.List;

/**
 * Une variante et sa grille tarifaire.
 *
 * <p>C'est ELLE qui se vend, pas le produit (D-01). Un sac de ciment sans
 * declinaison reelle a quand meme UNE variante, dite « par defaut » : un seul
 * chemin de code, toujours.</p>
 *
 * <p>{@link PalierPrix} est reutilise tel quel : c'etait deja un DTO, pas une
 * entite. En redefinir un second donnerait deux representations du meme prix,
 * qui finiraient par diverger.</p>
 */
public record VueVariante(
        Long id,
        Long produitId,
        String sku,
        String libelle,
        boolean parDefaut,
        String statut,
        List<PalierPrix> paliers,
        /**
         * Les valeurs d'attribut qui définissent cette déclinaison.
         *
         * <p>C'est ce qui permet à la vitrine d'afficher des sélecteurs plutôt
         * qu'une liste d'intitulés : « Taille : 42, 43 » et « Couleur : Blanc,
         * Noir » se déduisent des valeurs, pas du texte libre.</p>
         *
         * <p>Vide sur les déclinaisons créées avant le référentiel — elles
         * restent parfaitement valides, elles ne bénéficient simplement pas
         * des sélecteurs.</p>
         */
        List<ValeurChoisie> valeurs) {

    /** Une valeur retenue, avec de quoi l'afficher sans relire le référentiel. */
    public record ValeurChoisie(
            Long valeurId,
            Long attributId,
            String attribut,
            String libelle,
            String valeurAffichage) {

        public static ValeurChoisie de(ValeurAttribut v) {
            return new ValeurChoisie(v.getId(), v.getAttribut().getId(),
                    v.getAttribut().getNom(), v.getLibelle(), v.getValeurAffichage());
        }
    }

    /**
     * @param valeurs à charger DANS la transaction. La collection est
     *                {@code LAZY} : y toucher après coup lèverait, et
     *                seulement à l'exécution.
     */
    public static VueVariante de(Variante v, Long produitId, List<PalierPrix> paliers) {
        List<ValeurChoisie> valeurs = v.getValeurs().stream()
                .sorted(Comparator.comparing((ValeurAttribut x) -> x.getAttribut().getNom())
                        .thenComparingInt(ValeurAttribut::getOrdre))
                .map(ValeurChoisie::de)
                .toList();

        return new VueVariante(v.getId(), produitId, v.getSku(), v.getLibelle(),
                v.estParDefaut(), v.getStatut(), paliers, valeurs);
    }
}
