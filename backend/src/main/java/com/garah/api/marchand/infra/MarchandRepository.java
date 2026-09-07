package com.garah.api.marchand.infra;

import com.garah.api.marchand.domaine.Marchand;
import com.garah.api.marchand.domaine.NomMarchand;
import com.garah.api.marchand.domaine.StatutMarchand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MarchandRepository extends JpaRepository<Marchand, Long> {

    Optional<Marchand> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    Page<Marchand> findByStatut(StatutMarchand statut, Pageable pagination);

    /**
     * Recherche par nom ou par code.
     *
     * <p>Le back-office cherche indifferemment « Ets Ngono » ou « M-042 » :
     * obliger a choisir un champ ferait echouer une recherche sur deux.</p>
     */
    Page<Marchand> findByNomContainingIgnoreCaseOrCodeContainingIgnoreCase(
            String nom, String code, Pageable pagination);

    /**
     * Le nom de plusieurs marchands, en <b>une</b> requête.
     *
     * <p>Sert aux listes qui affichent « de qui vient ce produit ». Charger le
     * marchand ligne par ligne ferait une requête par produit affiché — le
     * genre de coût qu'on ne remarque qu'une fois en production.</p>
     *
     * <p>C'est une <b>projection</b> : deux colonnes, aucune entité. Le domaine
     * appelant ne peut donc pas modifier un marchand par inadvertance.</p>
     */
    @Query("SELECT new com.garah.api.marchand.domaine.NomMarchand(m.id, m.nom) "
           + "FROM Marchand m WHERE m.id IN :ids")
    List<NomMarchand> nomsPar(@Param("ids") Collection<Long> ids);

    /**
     * Le prochain code marchand, tiré d'une séquence PostgreSQL (V23).
     *
     * <p>Même raison qu'en V17 et V20 : une séquence est atomique et ne bloque
     * personne. Saisi à la main, le code produisait « 202020 » — une valeur qui
     * ne dit rien et qu'il fallait inventer à chaque création.</p>
     */
    @Query(value = "SELECT nextval('marchand_code_seq')", nativeQuery = true)
    long prochainCode();
}
