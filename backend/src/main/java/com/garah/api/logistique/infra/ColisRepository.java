package com.garah.api.logistique.infra;

import com.garah.api.logistique.domaine.Colis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ColisRepository extends JpaRepository<Colis, Long> {

    Optional<Colis> findByNumeroSuivi(String numeroSuivi);

    List<Colis> findByExpeditionId(Long expeditionId);

    @Query("""
            SELECT DISTINCT c FROM Colis c
              LEFT JOIN FETCH c.lignes
             WHERE c.id = :id
            """)
    Optional<Colis> chargerAvecLignes(Long id);
}
