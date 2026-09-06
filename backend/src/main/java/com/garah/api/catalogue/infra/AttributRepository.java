package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.Attribut;
import com.garah.api.catalogue.domaine.ValeurAttribut;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttributRepository extends JpaRepository<Attribut, Long> {

    Optional<Attribut> findByCode(String code);
}
