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
     * Charge un produit avec ses variantes.
     *
     * <p>⚠️ <b>Pourquoi DEUX methodes plutot qu'une seule.</b> La premiere
     * version faisait les deux {@code JOIN FETCH} dans la meme requete, en
     * pariant sur un simple produit cartesien dedoublonne par
     * {@code DISTINCT}. Hibernate refuse purement et simplement :</p>
     *
     * <pre>MultipleBagFetchException: cannot simultaneously fetch multiple bags:
     *     [Produit.medias, Produit.variantes]</pre>
     *
     * <p>Un « bag » est une {@code List} sans colonne d'ordre : Hibernate ne
     * peut pas savoir quelle ligne du produit cartesien appartient a quelle
     * collection, donc il refuse d'essayer plutot que de renvoyer des doublons
     * silencieux. <b>Le refus est le bon comportement.</b></p>
     *
     * <p>La correction consiste a charger <b>une collection a la fois</b>. Les
     * deux requetes renvoient la MEME instance geree — c'est le contexte de
     * persistance qui les reunit, sans produit cartesien.</p>
     *
     * @see #chargerAvecMedias(Long)
     */
    @Query("""
            SELECT p FROM Produit p
              LEFT JOIN FETCH p.variantes
             WHERE p.id = :id
            """)
    Optional<Produit> chargerAvecVariantes(Long id);

    /**
     * Initialise les medias du produit deja charge dans la transaction.
     *
     * <p>A appeler dans la MEME transaction que
     * {@link #chargerAvecVariantes(Long)} : Hibernate reconnait l'entite deja
     * presente dans le contexte de persistance et se contente de remplir sa
     * collection {@code medias}.</p>
     */
    @Query("""
            SELECT p FROM Produit p
              LEFT JOIN FETCH p.medias
             WHERE p.id = :id
            """)
    Optional<Produit> chargerAvecMedias(Long id);
}
