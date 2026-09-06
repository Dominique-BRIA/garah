package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.InfoVenteVariante;
import com.garah.api.catalogue.domaine.Variante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
