package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.TentativePaiement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TentativePaiementRepository extends JpaRepository<TentativePaiement, Long> {

    List<TentativePaiement> findByPaiementIdOrderByDateTentativeDesc(Long paiementId);
}
