package com.garah.api.catalogue.domaine;


import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;

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

    /**
     * ⚠️ {@code url} est <b>obligatoire</b> depuis D-21, {@code cleObjet} ne
     * l'est plus.
     *
     * <p>Avant, le frontend recevait une clé et la préfixait lui-même avec
     * {@code baseUrlMedias}. Ce modèle ne fonctionne plus avec un bucket
     * privé : construire l'adresse demande une <b>signature</b>, donc la clé
     * secrète — qu'un frontend ne doit évidemment jamais détenir.</p>
     *
     * <p>{@code cleObjet} reste exposé pour le back-office (c'est ce que la
     * base stocke, et ce qu'on montre en cas d'incident), mais aucun affichage
     * ne doit plus s'appuyer dessus.</p>
     */
    public record MediaResume(Long id, String type, String cleObjet, String url,
                              boolean principal, int ordre) {
    }

    /**
     * @param versUrl transforme une clé d'objet en adresse affichable —
     *                concaténation ou signature selon le bucket. Le DTO ignore
     *                laquelle des deux, et c'est voulu.
     */
    public static DetailProduit de(Produit p, UnaryOperator<String> versUrl) {
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
                        .map(m -> new MediaResume(m.getId(), m.getType().name(),
                                m.getCleObjet(), versUrl.apply(m.getCleObjet()),
                                m.estPrincipal(), m.getOrdre()))
                        .toList());
    }
}
