package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.CategorieProduit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategorieProduitRepository extends JpaRepository<CategorieProduit, Long> {

    Optional<CategorieProduit> findBySlug(String slug);

    List<CategorieProduit> findByParentIsNullOrderByOrdreAsc();

    List<CategorieProduit> findByParentIdOrderByOrdreAsc(Long parentId);
}
