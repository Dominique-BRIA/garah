package com.garah.api.commerce.domaine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Une commande telle qu'on l'affiche au client et au back-office. */
public record DetailCommande(
        Long id,
        String numero,
        String statut,
        Long pointRecuperationId,
        String langue,
        BigDecimal montantArticles,
        BigDecimal montantFrais,
        BigDecimal montantRemise,
        BigDecimal montantTotal,
        BigDecimal montantTva,
        String devise,
        Instant dateCreation,
        List<Ligne> lignes) {

    /**
     * Une ligne, telle qu'elle a été figée à la commande.
     *
     * <p>{@code designation} et {@code prixUnitaire} viennent de la ligne, pas
     * du catalogue : c'est ce qui rend une facture de mars encore juste en
     * septembre.</p>
     */
    public record Ligne(
            /*
             * 🎯 L'IDENTIFIANT DE LA LIGNE, et pas seulement celui de la
             * variante.
             *
             * Un retour désigne des LIGNES DE COMMANDE (D-12) : c'est la ligne
             * qui porte le prix figé, donc le montant remboursable. Sans cet
             * identifiant, le client voyait ses articles mais ne pouvait en
             * désigner aucun — l'écran « demander un retour » était
             * impossible à écrire.
             */
            Long id,
            Long varianteId,
            String designation,
            int quantite,
            BigDecimal prixUnitaire,
            BigDecimal montantLigne,
            BigDecimal montantTva) {
    }

    public static DetailCommande de(Commande c) {
        return new DetailCommande(
                c.getId(), c.getNumero(), c.getStatut().name(),
                c.getPointRecuperationId(), c.getLangue(),
                c.getMontantArticles(), c.getMontantFrais(), c.getMontantRemise(),
                c.getMontantTotal(), c.getMontantTva(), c.getDevise(), c.getDateCreation(),
                c.getLignes().stream()
                        .map(l -> new Ligne(l.getId(), l.getVarianteId(), l.getDesignation(),
                                l.getQuantite(), l.getPrixUnitaire(),
                                l.getMontantLigne(), l.getMontantTva()))
                        .toList());
    }
}
