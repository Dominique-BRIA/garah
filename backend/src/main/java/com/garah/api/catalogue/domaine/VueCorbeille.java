package com.garah.api.catalogue.domaine;

import java.time.Instant;

/**
 * Un produit en corbeille, tel qu'on le montre avant de trancher.
 *
 * <p>Volontairement <b>pauvre</b>. On y met ce qui permet de répondre à une
 * seule question — « est-ce bien celui-là que je voulais jeter ? » — et rien
 * de plus. Prix, stock et déclinaisons n'ont plus de sens pour un produit
 * retiré du catalogue : les afficher inviterait à décider sur des chiffres qui
 * ne veulent plus rien dire.</p>
 *
 * @param statut le statut d'AVANT la mise à la corbeille, conservé intact —
 *               c'est celui que le produit retrouvera s'il est restauré
 * @param dateSuppression sert à dire « il y a trois jours », qui vaut mieux
 *                        qu'une date brute quand il s'agit de se rappeler
 *                        d'un geste qu'on a soi-même fait
 */
public record VueCorbeille(
        Long id,
        String reference,
        String nom,
        String statut,
        String categorieNom,
        Instant dateSuppression) {
}
