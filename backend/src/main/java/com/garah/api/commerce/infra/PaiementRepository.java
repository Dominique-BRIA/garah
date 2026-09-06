package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.Paiement;
import com.garah.api.commerce.domaine.StatutPaiement;
import com.garah.api.commerce.domaine.TypePaiement;
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
}
