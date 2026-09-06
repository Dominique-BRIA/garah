package com.garah.api.surveillance.infra;

import com.garah.api.surveillance.domaine.AlerteSecurite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AlerteSecuriteRepository extends JpaRepository<AlerteSecurite, Long> {

    List<AlerteSecurite> findByClientIdOrderByDateCreationDesc(Long clientId);

    /**
     * Les alertes qui attendent une decision humaine, les plus graves d abord.
     *
     * <p>L ordre compte : une file d attente triee par date noierait une
     * alerte CRITIQUE sous trente alertes FAIBLES.</p>
     */
    @Query("""
            SELECT a FROM AlerteSecurite a
             WHERE a.statut IN ('OUVERTE', 'EN_COURS')
             ORDER BY CASE a.gravite
                        WHEN com.garah.api.surveillance.domaine.GraviteEvenement.CRITIQUE THEN 0
                        WHEN com.garah.api.surveillance.domaine.GraviteEvenement.HAUTE    THEN 1
                        WHEN com.garah.api.surveillance.domaine.GraviteEvenement.MOYENNE  THEN 2
                        ELSE 3 END,
                      a.dateCreation
            """)
    List<AlerteSecurite> aTraiter();

    boolean existsByClientIdAndTypeAndStatutIn(Long clientId, String type, List<String> statuts);
}
