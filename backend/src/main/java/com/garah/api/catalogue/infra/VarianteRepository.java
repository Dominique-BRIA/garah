package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.DesignationVariante;
import com.garah.api.catalogue.domaine.InfoVenteVariante;
import com.garah.api.catalogue.domaine.Variante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VarianteRepository extends JpaRepository<Variante, Long> {

    Optional<Variante> findBySku(String sku);

    boolean existsBySku(String sku);

    List<Variante> findByProduitId(Long produitId);

    long countByProduitIdAndStatut(Long produitId, String statut);

    /**
     * Le contrat de vente d'une variante, pour le domaine commerce.
     *
     * <p>Une <b>projection</b> : la requête construit directement le record,
     * sans charger d'entité. Elle lit six colonnes au lieu d'une trentaine, et
     * surtout elle ne renvoie <b>aucun objet mutable</b> — le commerce ne peut
     * donc pas modifier le catalogue par accident.</p>
     */
    @Query("""
            SELECT new com.garah.api.catalogue.domaine.InfoVenteVariante(
                       v.id, p.id, p.nom, v.libelle, p.marchandId,
                       p.categorie.id, p.tauxTva, v.statut, p.statut)
              FROM Variante v JOIN v.produit p
             WHERE v.id = :varianteId
            """)
    Optional<InfoVenteVariante> infoVente(Long varianteId);

    /**
     * De quoi designer plusieurs declinaisons, en <b>une</b> requete.
     *
     * <p>Sert aux ecrans d'un autre domaine — le stock affiche « Chaussure
     * Nike — Taille 42 », pas « variante 42 ». Charger la variante ligne par
     * ligne ferait une requete par ligne du tableau.</p>
     */
    @Query("""
            SELECT new com.garah.api.catalogue.domaine.DesignationVariante(
                       v.id, v.sku, v.libelle, p.id, p.nom)
              FROM Variante v JOIN v.produit p
             WHERE v.id IN :ids
            """)
    List<DesignationVariante> designationsPar(@Param("ids") Collection<Long> ids);

    /**
     * Les declinaisons dont le produit ou l'intitule correspond a la recherche.
     *
     * <p>Le stock ne connait que des identifiants de variante : chercher
     * « chaussure » dans une liste de stocks demande donc de passer par le
     * catalogue d'abord, puis de filtrer les stocks sur les identifiants
     * trouves.</p>
     */
    @Query("""
            SELECT v.id FROM Variante v JOIN v.produit p
             WHERE LOWER(p.nom) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                OR LOWER(v.libelle) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                OR LOWER(v.sku) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
            """)
    List<Long> idsCorrespondant(@Param("recherche") String recherche);
}
