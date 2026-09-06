package com.garah.api.catalogue.domaine;


import java.math.BigDecimal;
import java.util.List;

/**
 * La fiche complète d'un produit.
 *
 * <p>Remarque ce qui n'y figure <b>pas</b> : {@code creePar}, {@code modifiePar},
 * {@code dateModification}. Ce sont des données internes. Les exposer
 * renseignerait un concurrent sur l'organisation de l'entreprise — et c'est
 * précisément ce qu'un DTO permet d'éviter, contrairement à une entité
 * renvoyée telle quelle.</p>
 */
public record DetailProduit(
        Long id,
        String reference,
        String nom,
        String slug,
        String description,
        String statut,
        BigDecimal tauxTva,
        Long marchandId,
        Categorie categorie,
        List<VarianteResumee> variantes,
        List<MediaResume> medias) {

    public record Categorie(Long id, String nom, String slug) {
    }

    public record VarianteResumee(Long id, String sku, String libelle, boolean parDefaut, String statut) {
    }

    public record MediaResume(Long id, String type, String cleObjet, boolean principal, int ordre) {
    }

    public static DetailProduit de(Produit p) {
        return new DetailProduit(
                p.getId(),
                p.getReference(),
                p.getNom(),
                p.getSlug(),
                p.getDescription(),
                p.getStatut().name(),
                p.getTauxTva(),
                p.getMarchandId(),
                new Categorie(p.getCategorie().getId(), p.getCategorie().getNom(),
                        p.getCategorie().getSlug()),
                p.getVariantes().stream()
                        .map(v -> new VarianteResumee(v.getId(), v.getSku(), v.getLibelle(),
                                v.estParDefaut(), v.getStatut()))
                        .toList(),
                p.getMedias().stream()
                        .map(m -> new MediaResume(m.getId(), m.getType().name(), m.getCleObjet(),
                                m.estPrincipal(), m.getOrdre()))
                        .toList());
    }
}
