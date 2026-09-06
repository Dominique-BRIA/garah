package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.CategorieResponsable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CategorieResponsableRepository extends JpaRepository<CategorieResponsable, Long> {

    Optional<CategorieResponsable> findByNom(String nom);
}
