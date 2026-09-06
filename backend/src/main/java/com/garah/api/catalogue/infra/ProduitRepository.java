package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.Produit;
import com.garah.api.catalogue.domaine.StatutProduit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProduitRepository extends JpaRepository<Produit, Long> {

    Optional<Produit> findBySlug(String slug);

    boolean existsByReference(String reference);

    boolean existsBySlug(String slug);

    /**
     * Le catalogue public : uniquement les produits publiés.
     *
     * <p>La pagination n'est pas une option. Sans elle, un {@code findAll()}
     * charge tout le catalogue en mémoire — ça marche avec 50 produits, ça
     * fait tomber le serveur avec 50 000. Et personne ne s'en aperçoit avant
     * que le catalogue ne grossisse.</p>
     */
    Page<Produit> findByStatut(StatutProduit statut, Pageable pagination);

    Page<Produit> findByCategorieIdAndStatut(Long categorieId, StatutProduit statut, Pageable pagination);

    /**
     * Charge un produit avec ses variantes ET ses médias en une seule requête.
     *
     * <p>⚠️ Deux {@code JOIN FETCH} sur deux collections DIFFERENTES d'une même
     * entité provoquent un produit cartésien : 3 variantes x 4 medias = 12
     * lignes renvoyees. Hibernate les dedoublonne grace au DISTINCT, mais la
     * base a bien transporte 12 lignes. C'est acceptable ici (des dizaines
     * d'elements au plus) ; ca ne le serait pas sur des collections de milliers
     * de lignes, ou il faudrait deux requetes separees.</p>
     */
    @Query("""
            SELECT DISTINCT p FROM Produit p
              LEFT JOIN FETCH p.variantes
              LEFT JOIN FETCH p.medias
             WHERE p.id = :id
            """)
    Optional<Produit> chargerComplet(Long id);
}
