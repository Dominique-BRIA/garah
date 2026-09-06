package com.garah.api.stock.infra;

import com.garah.api.stock.domaine.CompteurStock;
import com.garah.api.stock.domaine.MouvementStock;
import com.garah.api.stock.domaine.OrigineMouvement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MouvementStockRepository extends JpaRepository<MouvementStock, Long> {

    List<MouvementStock> findByStockIdOrderByDateOperationDesc(Long stockId);

    List<MouvementStock> findByOrigineTypeAndOrigineId(OrigineMouvement origineType, Long origineId);

    /**
     * Recalcule un compteur depuis son journal.
     *
     * <p>C'est le <b>test de réconciliation</b> : la somme des mouvements doit
     * égaler la valeur stockée dans {@code stock}. Si les deux divergent, une
     * écriture a été faite hors du service — et il vaut mieux l'apprendre par
     * une alerte nocturne que par un inventaire six mois plus tard.</p>
     *
     * <p>Même principe que le solde marchand du chapitre 03 : l'état est une
     * <b>projection</b> du journal, et une projection se vérifie.</p>
     */
    @Query("""
            SELECT COALESCE(SUM(m.quantite), 0) FROM MouvementStock m
             WHERE m.stockId = :stockId AND m.compteur = :compteur
            """)
    int recalculer(@Param("stockId") Long stockId, @Param("compteur") CompteurStock compteur);
}
