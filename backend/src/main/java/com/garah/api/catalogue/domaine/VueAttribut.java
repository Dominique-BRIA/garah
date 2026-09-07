package com.garah.api.catalogue.domaine;

import java.util.Comparator;
import java.util.List;

/**
 * Une dimension de déclinaison, avec ses valeurs.
 *
 * <p>C'est ce qu'Amazon appelle un <i>variation theme</i> : l'axe selon lequel
 * un produit se décline. Taille, Couleur, Conditionnement.</p>
 *
 * <p>Le référentiel est <b>partagé par tout le catalogue</b>. « Taille » est
 * défini une fois et réutilisé par tous les vêtements — sinon chaque produit
 * inventerait ses propres valeurs (« M », « m », « Medium », « Moyen ») et
 * aucun filtre transversal ne serait possible.</p>
 *
 * @param typeAffichage {@code LISTE} (déroulante) ou {@code PASTILLE} (carré
 *                      de couleur). Une couleur écrite en toutes lettres
 *                      oblige le client à imaginer « Bleu ciel ».
 */
public record VueAttribut(
        Long id,
        String code,
        String nom,
        String typeAffichage,
        List<VueValeur> valeurs) {

    /**
     * @param valeurAffichage la couleur, pour une pastille. Nulle ailleurs.
     * @param ordre 38 avant 39 avant 40. Trié alphabétiquement, on obtient
     *              « 10, 38, 9 » — une taille se lit dans l'ordre des tailles.
     */
    public record VueValeur(
            Long id,
            String code,
            String libelle,
            String valeurAffichage,
            int ordre) {

        public static VueValeur de(ValeurAttribut v) {
            return new VueValeur(v.getId(), v.getCode(), v.getLibelle(),
                    v.getValeurAffichage(), v.getOrdre());
        }
    }

    public static VueAttribut de(Attribut a) {
        List<VueValeur> valeurs = a.getValeurs().stream()
                .sorted(Comparator.comparingInt(ValeurAttribut::getOrdre)
                        .thenComparing(ValeurAttribut::getLibelle))
                .map(VueValeur::de)
                .toList();

        return new VueAttribut(a.getId(), a.getCode(), a.getNom(),
                a.getTypeAffichage(), valeurs);
    }
}
