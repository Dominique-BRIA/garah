package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.Panier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PanierRepository extends JpaRepository<Panier, Long> {

    Optional<Panier> findByClientIdAndStatut(Long clientId, String statut);

    @Query("""
            SELECT DISTINCT p FROM Panier p
              LEFT JOIN FETCH p.lignes
             WHERE p.clientId = :clientId AND p.statut = 'ACTIF'
            """)
    Optional<Panier> chargerActifAvecLignes(Long clientId);
}
