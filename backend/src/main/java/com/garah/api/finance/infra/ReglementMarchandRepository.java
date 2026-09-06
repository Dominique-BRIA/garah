package com.garah.api.finance.infra;

import com.garah.api.finance.domaine.ReglementMarchand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReglementMarchandRepository extends JpaRepository<ReglementMarchand, Long> {

    Optional<ReglementMarchand> findByNumero(String numero);

    List<ReglementMarchand> findByMarchandIdOrderByDateCreationDesc(Long marchandId);

    @Query(value = "SELECT nextval('reglement_numero_seq')", nativeQuery = true)
    long prochainNumero();
}
