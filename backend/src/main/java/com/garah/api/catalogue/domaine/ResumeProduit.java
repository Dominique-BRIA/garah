package com.garah.api.catalogue.domaine;

import java.util.function.UnaryOperator;


/**
 * La vue « liste » d'un produit : le strict nécessaire pour une vignette.
 *
 * <p>Un DTO de liste doit rester <b>petit</b>. Renvoyer le détail complet de
 * chaque produit d'une page de 24 multiplie par vingt le poids de la réponse —
 * ce qui se remarque tout de suite sur une connexion mobile camerounaise, et
 * jamais sur la fibre du développeur.</p>
 */
public record ResumeProduit(
        Long id,
        String reference,
        String nom,
        String slug,
        String statut,
        String clePhotoPrincipale,
        String urlPhotoPrincipale) {

    /**
     * @param versUrl transforme la clé en adresse affichable — concaténation
     *                ou signature selon que le bucket est public ou privé
     *                (D-21). Le DTO ignore laquelle des deux.
     *
     * <p>⚠️ {@code urlPhotoPrincipale} est le champ que la vitrine doit
     * utiliser. {@code clePhotoPrincipale} ne suffit plus : avec un bucket
     * privé, en déduire une adresse demande une signature, donc la clé
     * secrète.</p>
     */
    public static ResumeProduit de(Produit produit, UnaryOperator<String> versUrl) {
        String photo = produit.getMedias().stream()
                .filter(Media::estPrincipal)
                .map(Media::getCleObjet)
                .findFirst()
                .orElse(null);

        return new ResumeProduit(
                produit.getId(),
                produit.getReference(),
                produit.getNom(),
                produit.getSlug(),
                produit.getStatut().name(),
                photo,
                versUrl.apply(photo));
    }
}
