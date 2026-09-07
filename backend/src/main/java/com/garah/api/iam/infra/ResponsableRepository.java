package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.Responsable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResponsableRepository extends JpaRepository<Responsable, Long> {

    Optional<Responsable> findByMatricule(String matricule);

    /**
     * Charge un responsable AVEC ses catégories en une seule requête.
     *
     * <p>Sans ce {@code JOIN FETCH}, appeler {@code responsable.titre()} sur
     * 200 responsables déclencherait 200 requêtes supplémentaires — le
     * fameux problème <b>N+1</b>. Le code paraît identique ; seul le nombre
     * de requêtes change, et il ne se voit qu'en regardant les logs SQL.</p>
     */
    @Query("""
            SELECT DISTINCT r FROM Responsable r
              LEFT JOIN FETCH r.categories rc
              LEFT JOIN FETCH rc.categorie
             WHERE r.id = :id
            """)
    Optional<Responsable> chargerAvecCategories(Long id);

    boolean existsByMatricule(String matricule);

    /**
     * Le prochain matricule, tiré d'une séquence PostgreSQL (V25).
     *
     * <p>Même raison qu'en V17, V20 et V23 : {@code count(*) + 1} donne le même
     * matricule à deux créations simultanées, et la contrainte {@code UNIQUE}
     * fait alors échouer une création parfaitement valide.</p>
     */
    @Query(value = "SELECT nextval('responsable_matricule_seq')", nativeQuery = true)
    long prochainMatricule();

    /**
     * L'équipe, avec les catégories de chacun, en une seule requête.
     *
     * <p>Le {@code JOIN FETCH} n'est pas un raffinement : afficher le titre de
     * chaque responsable — c'est-à-dire sa catégorie principale — déclencherait
     * sinon une requête par ligne de la liste.</p>
     *
     * <p>⚠️ Pas de {@code Pageable} ici, et c'est délibéré : paginer une
     * requête qui charge une collection oblige Hibernate à tout ramener en
     * mémoire avant de découper. Une équipe se compte en dizaines, pas en
     * milliers — on la charge entière et on la trie côté service.</p>
     */
    @Query("""
            SELECT DISTINCT r FROM Responsable r
              LEFT JOIN FETCH r.categories rc
              LEFT JOIN FETCH rc.categorie
              LEFT JOIN FETCH r.utilisateur
             ORDER BY r.matricule
            """)
    List<Responsable> chargerToutAvecCategories();
}
