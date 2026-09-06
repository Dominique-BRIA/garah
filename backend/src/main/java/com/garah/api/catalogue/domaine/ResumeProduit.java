package com.garah.api.catalogue.domaine;


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
        String clePhotoPrincipale) {

    public static ResumeProduit de(Produit produit) {
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
                photo);
    }
}
