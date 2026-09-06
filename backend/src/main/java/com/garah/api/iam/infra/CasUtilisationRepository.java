package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.CasUtilisation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CasUtilisationRepository extends JpaRepository<CasUtilisation, Long> {

    Optional<CasUtilisation> findByCode(String code);

    List<CasUtilisation> findByModuleOrderByCode(String module);

    /**
     * Les permissions effectives d'un responsable.
     *
     * <p>C'est la requête la plus exécutée de toute l'application : elle tourne
     * à chaque appel authentifié. Elle est écrite en <b>SQL natif</b> et non en
     * JPQL, pour trois raisons :</p>
     * <ul>
     *   <li>elle utilise des CTE ({@code WITH}), que JPQL ne connaît pas ;</li>
     *   <li>elle ne charge aucune entité — juste une liste de codes ;</li>
     *   <li>elle doit rester lisible à côté du chapitre 03 §4, qui l'explique.</li>
     * </ul>
     *
     * <p><b>⚠️ Le point subtil, et le piège de D-02 :</b> le {@code NOT IN
     * (retraits)} s'applique à la FIN, sur l'union complète des catégories.
     * Si on soustrayait catégorie par catégorie, un {@code REMOVE} sur
     * {@code PRIX_MODIFIER} ne retirerait le droit que d'une catégorie —
     * l'autre le redonnerait aussitôt.</p>
     */
    @Query(value = """
            WITH depuis_categories AS (
                SELECT DISTINCT ccu.cas_utilisation_id
                  FROM responsable_categorie rc
                  JOIN categorie_cas_utilisation ccu ON ccu.categorie_id = rc.categorie_id
                 WHERE rc.responsable_id = :responsableId
            ),
            ajouts AS (
                SELECT cas_utilisation_id FROM responsable_cas_utilisation
                 WHERE responsable_id = :responsableId AND type = 'ADD'
            ),
            retraits AS (
                SELECT cas_utilisation_id FROM responsable_cas_utilisation
                 WHERE responsable_id = :responsableId AND type = 'REMOVE'
            )
            SELECT cu.code
              FROM cas_utilisation cu
             WHERE cu.statut = 'ACTIF'
               AND cu.id IN (SELECT cas_utilisation_id FROM depuis_categories
                             UNION
                             SELECT cas_utilisation_id FROM ajouts)
               AND cu.id NOT IN (SELECT cas_utilisation_id FROM retraits)
             ORDER BY cu.code
            """, nativeQuery = true)
    List<String> permissionsEffectives(@Param("responsableId") Long responsableId);
}
