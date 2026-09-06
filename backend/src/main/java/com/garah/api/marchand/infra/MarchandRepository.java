package com.garah.api.marchand.infra;

import com.garah.api.marchand.domaine.Marchand;
import com.garah.api.marchand.domaine.StatutMarchand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarchandRepository extends JpaRepository<Marchand, Long> {

    Optional<Marchand> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    Page<Marchand> findByStatut(StatutMarchand statut, Pageable pagination);

    /**
     * Recherche par nom ou par code.
     *
     * <p>Le back-office cherche indifferemment « Ets Ngono » ou « M-042 » :
     * obliger a choisir un champ ferait echouer une recherche sur deux.</p>
     */
    Page<Marchand> findByNomContainingIgnoreCaseOrCodeContainingIgnoreCase(
            String nom, String code, Pageable pagination);
}
