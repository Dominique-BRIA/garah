package com.garah.api.catalogue.domaine;

import java.math.BigDecimal;
import java.util.function.UnaryOperator;

/**
 * La vue « liste » d'un produit : le strict nécessaire pour une vignette.
 *
 * <p>Un DTO de liste doit rester <b>petit</b>. Renvoyer le détail complet de
 * chaque produit d'une page de 24 multiplie par vingt le poids de la réponse —
 * ce qui se remarque tout de suite sur une connexion mobile camerounaise, et
 * jamais sur la fibre du développeur.</p>
 *
 * <p>Petit ne veut pas dire amputé. Le marchand, la catégorie et le prix
 * d'appel <b>y figurent</b> : ce sont les trois colonnes qu'on lit dans une
 * liste pour décider sur quelle ligne cliquer. Les omettre obligerait à ouvrir
 * chaque fiche pour retrouver de qui vient le produit.</p>
 *
 * @param prixMin le plus bas de la grille, {@code null} tant qu'aucun prix
 *                n'est posé — un brouillon en cours n'en a pas encore
 */
public record ResumeProduit(
        Long id,
        String reference,
        String nom,
        String slug,
        String statut,
        String clePhotoPrincipale,
        String urlPhotoPrincipale,
        String marchandNom,
        String categorieNom,
        BigDecimal prixMin,
        String devise) {

    /**
     * @param versUrl transforme la clé en adresse affichable — concaténation
     *                ou signature selon que le bucket est public ou privé
     *                (D-21). Le DTO ignore laquelle des deux.
     * @param marchandNom résolu <b>en amont, pour toute la page</b>. Le produit
     *                    ne porte qu'un {@code marchandId} : les deux domaines
     *                    restent séparés, et le nom se lit en une requête
     *                    plutôt qu'une par ligne.
     * @param prix le prix d'appel, {@code null} si le produit n'en a pas encore
     *
     * <p>⚠️ {@code urlPhotoPrincipale} est le champ que la vitrine doit
     * utiliser. {@code clePhotoPrincipale} ne suffit plus : avec un bucket
     * privé, en déduire une adresse demande une signature, donc la clé
     * secrète.</p>
     */
    public static ResumeProduit de(Produit produit, UnaryOperator<String> versUrl,
                                   String marchandNom, PrixMinProduit prix) {
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
                versUrl.apply(photo),
                marchandNom,
                produit.getCategorie().getNom(),
                prix == null ? null : prix.prixMin(),
                prix == null ? null : prix.devise());
    }
}
