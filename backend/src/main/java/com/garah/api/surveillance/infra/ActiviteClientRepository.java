package com.garah.api.surveillance.infra;

import com.garah.api.surveillance.domaine.ActiviteClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActiviteClientRepository extends JpaRepository<ActiviteClient, Long> {

    /**
     * Le parcours d'un client, du plus récent au plus ancien.
     *
     * <p>⚠️ L'ordre suit l'index {@code activite_client_client_idx}, qui est
     * déclaré {@code (client_id, date_heure DESC)}. Trier autrement ferait
     * lire la table entière — sur un journal de navigation, c'est la requête
     * qui grossit le plus vite de toute l'application.</p>
     */
    Page<ActiviteClient> findByClientIdOrderByDateHeureDesc(Long clientId, Pageable pagination);
}
