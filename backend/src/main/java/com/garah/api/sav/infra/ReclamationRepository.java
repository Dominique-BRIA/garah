package com.garah.api.sav.infra;

import com.garah.api.sav.domaine.Reclamation;
import com.garah.api.sav.domaine.StatutReclamation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReclamationRepository extends JpaRepository<Reclamation, Long> {

    Optional<Reclamation> findByNumero(String numero);

    Page<Reclamation> findByClientIdOrderByDateCreationDesc(Long clientId, Pageable pagination);

    List<Reclamation> findByStatutOrderByDateCreationAsc(StatutReclamation statut);

    @Query(value = "SELECT nextval('reclamation_numero_seq')", nativeQuery = true)
    long prochainNumero();
}
