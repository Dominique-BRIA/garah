package com.garah.api.surveillance.infra;

import com.garah.api.surveillance.domaine.ScoreRisqueClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScoreRisqueClientRepository extends JpaRepository<ScoreRisqueClient, Long> {

    /** Le score COURANT : le plus recent. Les precedents sont conserves. */
    Optional<ScoreRisqueClient> findFirstByClientIdOrderByDateCalculDescIdDesc(Long clientId);

    List<ScoreRisqueClient> findByClientIdOrderByDateCalculDesc(Long clientId);
}
