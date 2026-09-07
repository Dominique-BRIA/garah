package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.Paiement;
import com.garah.api.commerce.domaine.StatutPaiement;
import com.garah.api.commerce.domaine.TypePaiement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaiementRepository extends JpaRepository<Paiement, Long> {

    Optional<Paiement> findByReferenceTransaction(String referenceTransaction);

    List<Paiement> findByCommandeIdOrderByDateInitiationDesc(Long commandeId);

    /**
     * Les paiements en attente qui ont bien atteint l'opérateur.
     *
     * <p>Alimente la réconciliation périodique. Le filtre sur la reference est
     * essentiel : un paiement sans référence n'a jamais été transmis, et
     * interroger Campay à son sujet n'aurait aucun sens.</p>
     */
    @Query("""
            SELECT p FROM Paiement p
             WHERE p.statut = :statut
               AND p.referenceTransaction IS NOT NULL
             ORDER BY p.dateInitiation
            """)
    List<Paiement> enAttenteAvecReference(@Param("statut") StatutPaiement statut);

    /**
     * La somme des mouvements confirmes d un type donne, pour une commande.
     *
     * <p>Sert deux controles :</p>
     * <ul>
     *   <li>a-t-on assez encaisse pour passer la commande a PAYEE ?</li>
     *   <li>le cumul rembourse depasse-t-il le cumul encaisse (I-27) ?</li>
     * </ul>
     *
     * <p>Le total n est stocke nulle part : c est une SOMME, exactement comme
     * le solde marchand du chapitre 03. Un total stocke finirait par mentir.</p>
     */
    @Query("""
            SELECT COALESCE(SUM(p.montant), 0) FROM Paiement p
             WHERE p.commandeId = :commandeId
               AND p.type = :type
               AND p.statut = :statut
            """)
    BigDecimal total(@Param("commandeId") Long commandeId,
                     @Param("type") TypePaiement type,
                     @Param("statut") StatutPaiement statut);

    /**
     * La liste du back-office : encaissements et remboursements.
     *
     * <p>Les deux ensemble, et c'est voulu : « qu'est-il arrive a l'argent de
     * cette commande ? » ne se repond pas en consultant deux ecrans. Le filtre
     * {@code type} permet de les separer quand on cherche autre chose.</p>
     *
     * <p>La recherche porte sur la reference de transaction — c'est elle qu'on
     * recopie depuis le SMS de l'operateur quand un client conteste.</p>
     */
    @Query(value = """
            SELECT p FROM Paiement p
             WHERE (:statut IS NULL OR p.statut = :statut)
               AND (:type IS NULL OR p.type = :type)
               AND (:recherche IS NULL
                    OR LOWER(p.referenceTransaction)
                       LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
             ORDER BY p.dateInitiation DESC
            """,
            countQuery = """
            SELECT count(p) FROM Paiement p
             WHERE (:statut IS NULL OR p.statut = :statut)
               AND (:type IS NULL OR p.type = :type)
               AND (:recherche IS NULL
                    OR LOWER(p.referenceTransaction)
                       LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
            """)
    Page<Paiement> administration(@Param("statut") StatutPaiement statut,
                                  @Param("type") TypePaiement type,
                                  @Param("recherche") String recherche,
                                  Pageable pagination);
}
