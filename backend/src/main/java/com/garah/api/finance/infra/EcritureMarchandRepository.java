package com.garah.api.finance.infra;

import com.garah.api.finance.domaine.EcritureMarchand;
import com.garah.api.finance.domaine.OrigineEcriture;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface EcritureMarchandRepository extends JpaRepository<EcritureMarchand, Long> {

    /**
     * LE solde. Il n est stocke nulle part.
     *
     * <p>C est une SOMME, recalculee a chaque demande. Un solde stocke finit
     * toujours par mentir : il suffit d une ecriture ratee, et plus personne
     * ne peut prouver la verite (correction A3).</p>
     */
    @Query("""
            SELECT COALESCE(SUM(e.montant), 0) FROM EcritureMarchand e
             WHERE e.marchandId = :marchandId AND e.devise = :devise
            """)
    BigDecimal solde(@Param("marchandId") Long marchandId, @Param("devise") String devise);

    Page<EcritureMarchand> findByMarchandIdOrderByDateEcritureDesc(Long marchandId,
                                                                   Pageable pagination);

    /**
     * Les ecritures deja produites par une piece.
     *
     * <p>Sert a l IDEMPOTENCE : confirmer deux fois un paiement ne doit pas
     * ecrire deux fois la vente. Le webhook est rejoue (chapitre 13), donc
     * tout ce qu il declenche doit l etre aussi.</p>
     */
    List<EcritureMarchand> findByOrigineTypeAndOrigineId(OrigineEcriture origineType,
                                                         Long origineId);

    boolean existsByOrigineTypeAndOrigineId(OrigineEcriture origineType, Long origineId);
}
