package com.garah.api.mesure.infra;

import com.garah.api.mesure.domaine.VueProduit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface VueProduitRepository extends JpaRepository<VueProduit, Long> {

    long countByProduitId(Long produitId);

    /**
     * Purge le detail au-dela de la retention (D-15).
     *
     * <p>⚠️ A ecrire EN MEME TEMPS que la collecte, pas « plus tard ». Une
     * table de detail sans purge est une bombe a retardement : on la decouvre
     * le jour ou elle fait 40 Go et ou la base ralentit.</p>
     *
     * <p>L index vue_produit_purge_idx sur date_heure existe precisement pour
     * que cette suppression soit rapide.</p>
     */
    @Modifying
    @Query("DELETE FROM VueProduit v WHERE v.dateHeure < :limite")
    int purger(Instant limite);
}
