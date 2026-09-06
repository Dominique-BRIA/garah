package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.Tarification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TarificationRepository extends JpaRepository<Tarification, Long> {

    List<Tarification> findByVarianteIdOrderByQuantiteMinAsc(Long varianteId);

    /** Le produit a-t-il au moins un prix sur une variante active ? (invariant I-12) */
    @Query("""
            SELECT count(t) > 0 FROM Tarification t
             WHERE t.variante.produit.id = :produitId
               AND t.variante.statut = 'ACTIVE'
            """)
    boolean produitAUnPrix(Long produitId);
}
