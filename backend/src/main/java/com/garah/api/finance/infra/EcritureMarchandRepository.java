package com.garah.api.finance.infra;

import com.garah.api.finance.domaine.EcritureMarchand;
import com.garah.api.finance.domaine.OrigineEcriture;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
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

    /**
     * Le solde de PLUSIEURS marchands, en une requete.
     *
     * <p>La liste « qui doit-on payer ? » interroge sinon un solde par ligne :
     * vingt-cinq requetes pour vingt-cinq marchands, sur une base distante.
     * C est la regle du projet — une requete par page, jamais une par ligne.</p>
     *
     * <p>⚠️ Un marchand SANS aucune ecriture n apparait pas dans le resultat.
     * Ce n est pas un oubli : il n a rien vendu, son solde est zero, et une
     * jointure exterieure depuis marchand creerait ici une dependance de
     * finance vers marchand. L appelant complete a zero.</p>
     */
    @Query("""
            SELECT e.marchandId, SUM(e.montant) FROM EcritureMarchand e
             WHERE e.marchandId IN :marchandIds AND e.devise = :devise
             GROUP BY e.marchandId
            """)
    List<Object[]> soldesPar(@Param("marchandIds") Collection<Long> marchandIds,
                             @Param("devise") String devise);

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
