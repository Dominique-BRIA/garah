package com.garah.api.mesure.infra;

import com.garah.api.mesure.domaine.Favori;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FavoriRepository extends JpaRepository<Favori, Favori.Cle> {

    List<Favori> findByCleClientId(Long clientId);

    long countByCleProduitId(Long produitId);
}
