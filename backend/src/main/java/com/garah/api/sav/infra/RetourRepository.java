package com.garah.api.sav.infra;

import com.garah.api.sav.domaine.Retour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RetourRepository extends JpaRepository<Retour, Long> {

    Optional<Retour> findByNumero(String numero);

    List<Retour> findByCommandeId(Long commandeId);

    @Query("""
            SELECT DISTINCT r FROM Retour r
              LEFT JOIN FETCH r.lignes
             WHERE r.id = :id
            """)
    Optional<Retour> chargerAvecLignes(Long id);

    @Query(value = "SELECT nextval('retour_numero_seq')", nativeQuery = true)
    long prochainNumero();
}
