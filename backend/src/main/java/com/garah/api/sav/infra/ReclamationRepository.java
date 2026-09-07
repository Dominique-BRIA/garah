package com.garah.api.sav.infra;

import com.garah.api.sav.domaine.Reclamation;
import com.garah.api.sav.domaine.StatutReclamation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReclamationRepository extends JpaRepository<Reclamation, Long> {

    Optional<Reclamation> findByNumero(String numero);

    Page<Reclamation> findByClientIdOrderByDateCreationDesc(Long clientId, Pageable pagination);

    List<Reclamation> findByStatutOrderByDateCreationAsc(StatutReclamation statut);

    @Query(value = "SELECT nextval('reclamation_numero_seq')", nativeQuery = true)
    long prochainNumero();

    /**
     * La liste du back-office.
     *
     * <p>Les plus ANCIENNES d abord. Une reclamation qui traine est un client
     * qui s enerve : c est celle-la qu il faut voir en haut, pas la derniere
     * arrivee. C est l inverse des listes de catalogue, et c est voulu.</p>
     *
     * <p>La recherche porte sur le numero de reclamation ET sur le numero de
     * commande : quand un client rappelle, il donne le second — il n a souvent
     * jamais note le premier.</p>
     */
    @Query(value = """
            SELECT r FROM Reclamation r
             WHERE (:statut IS NULL OR r.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(r.numero) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                    OR r.commandeId IN (SELECT c.id FROM Commande c
                                         WHERE LOWER(c.numero)
                                               LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))))
             ORDER BY r.dateCreation ASC
            """)
    Page<Reclamation> administration(@Param("statut") StatutReclamation statut,
                                     @Param("recherche") String recherche,
                                     Pageable pagination);
}
