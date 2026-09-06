package com.garah.api.catalogue.domaine;

import java.math.BigDecimal;

/**
 * Tout ce que le domaine commerce a besoin de savoir d'une variante pour la
 * vendre — et rien de plus.
 *
 * <p><b>Pourquoi ce record existe.</b> Le domaine commerce pourrait charger
 * l'entité {@code Variante} et naviguer vers {@code produit}. Il lirait alors
 * des dizaines de champs dont il n'a que faire, et surtout il dépendrait de la
 * <b>structure interne</b> du catalogue : renommer un champ casserait la
 * commande.</p>
 *
 * <p>Ce record est un <b>contrat entre domaines</b>. Le catalogue peut
 * réorganiser ses entités tant qu'il continue à le remplir.</p>
 */
public record InfoVenteVariante(
        Long varianteId,
        Long produitId,
        String nomProduit,
        String libelleVariante,
        Long marchandId,
        Long categorieProduitId,
        BigDecimal tauxTva,
        String statutVariante,
        StatutProduit statutProduit) {

    /** La désignation figée dans la ligne de commande. */
    public String designation() {
        return libelleVariante == null || libelleVariante.isBlank()
                ? nomProduit
                : nomProduit + " — " + libelleVariante;
    }

    /** Vendable seulement si le produit est publié ET la variante active. */
    public boolean estVendable() {
        return statutProduit == StatutProduit.PUBLIE && "ACTIVE".equals(statutVariante);
    }
}
