package com.garah.api.logistique.infra;

import com.garah.api.logistique.domaine.Lieu;
import com.garah.api.logistique.domaine.TypeLieu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LieuRepository extends JpaRepository<Lieu, Long> {

    List<Lieu> findByTypeAndStatutOrderByVilleAscNomAsc(TypeLieu type, String statut);

    List<Lieu> findByTypeAndVilleAndStatut(TypeLieu type, String ville, String statut);
}
