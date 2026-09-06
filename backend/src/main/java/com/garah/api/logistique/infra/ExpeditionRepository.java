package com.garah.api.logistique.infra;

import com.garah.api.logistique.domaine.Expedition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ExpeditionRepository extends JpaRepository<Expedition, Long> {

    Optional<Expedition> findByNumero(String numero);

    List<Expedition> findByCommandeId(Long commandeId);

    @Query("""
            SELECT DISTINCT e FROM Expedition e
              LEFT JOIN FETCH e.colis
             WHERE e.id = :id
            """)
    Optional<Expedition> chargerAvecColis(Long id);

    @Query(value = "SELECT nextval('expedition_numero_seq')", nativeQuery = true)
    long prochainNumero();
}
