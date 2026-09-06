package com.garah.api.logistique.infra;

import com.garah.api.logistique.domaine.RetraitMarchandise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RetraitMarchandiseRepository extends JpaRepository<RetraitMarchandise, Long> {

    Optional<RetraitMarchandise> findByCodeRetrait(String codeRetrait);

    Optional<RetraitMarchandise> findByExpeditionId(Long expeditionId);
}
