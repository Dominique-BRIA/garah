package com.garah.api.mesure.infra;

import com.garah.api.mesure.domaine.Favori;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FavoriRepository extends JpaRepository<Favori, Favori.Cle> {

    List<Favori> findByCleClientId(Long clientId);

    /**
     * Le plus recemment ajoute en tete : c est l ordre dans lequel on relit sa
     * propre liste d envies. Par identifiant, elle serait triee par ordre de
     * creation des PRODUITS, ce qui ne veut rien dire pour celui qui regarde.
     */
    List<Favori> findByCleClientIdOrderByDateAjoutDesc(Long clientId);

    long countByCleProduitId(Long produitId);
}
