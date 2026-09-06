package com.garah.api.surveillance.infra;

import com.garah.api.surveillance.domaine.EvenementSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface EvenementSecuriteRepository extends JpaRepository<EvenementSecurite, Long> {

    List<EvenementSecurite> findByUtilisateurIdOrderByDateHeureDesc(Long utilisateurId);

    /**
     * Compte les échecs récents. C'est le premier signal du score de risque
     * (spec §19) : « 7 échecs de connexion en 1 h ».
     */
    long countByUtilisateurIdAndTypeAndDateHeureAfter(
            Long utilisateurId, TypeEvenementSecurite type, Instant depuis);
}
