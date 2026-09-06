package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.Responsable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ResponsableRepository extends JpaRepository<Responsable, Long> {

    Optional<Responsable> findByMatricule(String matricule);

    /**
     * Charge un responsable AVEC ses catégories en une seule requête.
     *
     * <p>Sans ce {@code JOIN FETCH}, appeler {@code responsable.titre()} sur
     * 200 responsables déclencherait 200 requêtes supplémentaires — le
     * fameux problème <b>N+1</b>. Le code paraît identique ; seul le nombre
     * de requêtes change, et il ne se voit qu'en regardant les logs SQL.</p>
     */
    @Query("""
            SELECT DISTINCT r FROM Responsable r
              LEFT JOIN FETCH r.categories rc
              LEFT JOIN FETCH rc.categorie
             WHERE r.id = :id
            """)
    Optional<Responsable> chargerAvecCategories(Long id);
}
