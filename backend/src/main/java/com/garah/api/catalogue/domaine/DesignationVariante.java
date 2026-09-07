package com.garah.api.catalogue.domaine;

/**
 * De quoi désigner une déclinaison dans une liste, et rien de plus.
 *
 * <p>Un écran de stock qui afficherait « variante 42 : 7 disponibles » ne
 * servirait à rien : personne ne sait ce qu'est la variante 42. Il faut le nom
 * du produit et l'intitulé de la déclinaison — donc traverser la frontière du
 * catalogue.</p>
 *
 * <p>C'est une <b>projection</b> : cinq colonnes, aucune entité. Le domaine
 * appelant peut lire, jamais modifier le catalogue par inadvertance.</p>
 */
public record DesignationVariante(
        Long varianteId,
        String sku,
        String libelle,
        Long produitId,
        String produitNom) {

    /** « Chaussure Nike — Taille 42 », ou le seul intitulé s'ils se confondent. */
    public String complete() {
        return libelle.equals(produitNom) ? produitNom : produitNom + " — " + libelle;
    }
}
