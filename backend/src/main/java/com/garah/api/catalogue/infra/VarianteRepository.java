package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.Variante;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VarianteRepository extends JpaRepository<Variante, Long> {

    Optional<Variante> findBySku(String sku);

    boolean existsBySku(String sku);

    List<Variante> findByProduitId(Long produitId);

    long countByProduitIdAndStatut(Long produitId, String statut);
}
