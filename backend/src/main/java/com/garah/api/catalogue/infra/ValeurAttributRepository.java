package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.ValeurAttribut;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ValeurAttributRepository extends JpaRepository<ValeurAttribut, Long> {

    List<ValeurAttribut> findByAttributIdOrderByOrdreAsc(Long attributId);
}
