package com.garah.api.mesure.domaine;

/**
 * Un produit dans le classement des tendances.
 *
 * <p>La « dynamique recente » de la section 20 : on ne classe pas par volume
 * absolu — sinon le meme best-seller occuperait la premiere place pendant
 * trois ans — mais par PROGRESSION.</p>
 *
 * @param croissance rapport entre la periode recente et la precedente
 */
public record ProduitTendance(
        Long produitId,
        String nom,
        long ventesRecentes,
        long ventesPrecedentes,
        long vuesRecentes,
        double croissance) {
}
