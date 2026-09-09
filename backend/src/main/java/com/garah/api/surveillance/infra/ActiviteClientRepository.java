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
     *
     * <h2>⚠️ Pourquoi l'identifiant départage</h2>
     *
     * <p>La date seule ne suffit pas. Deux gestes posés dans la même
     * milliseconde — mettre au panier puis commander, sur une requête rapide —
     * portent le même {@code date_heure}, et PostgreSQL rend alors l'ordre
     * qu'il veut. Le parcours se lisait à l'envers une fois sur deux.</p>
     *
     * <p>C'est un test qui l'a montré : il passait seul et échouait dans la
     * suite complète, la machine étant alors assez chargée pour que les
     * écritures tombent dans la même milliseconde. Une instabilité de ce genre
     * finit toujours par être mise sur le compte de la malchance.</p>
     */
    Page<ActiviteClient> findByClientIdOrderByDateHeureDescIdDesc(
            Long clientId, Pageable pagination);
}
