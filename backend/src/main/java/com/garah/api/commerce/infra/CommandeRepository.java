package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.Commande;
import com.garah.api.commerce.domaine.StatutCommande;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CommandeRepository extends JpaRepository<Commande, Long> {

    Optional<Commande> findByNumero(String numero);

    Page<Commande> findByClientIdOrderByDateCreationDesc(Long clientId, Pageable pagination);

    @Query("""
            SELECT DISTINCT c FROM Commande c
              LEFT JOIN FETCH c.lignes
             WHERE c.id = :id
            """)
    Optional<Commande> chargerAvecLignes(Long id);

    /**
     * Le prochain numero, tire d'une sequence PostgreSQL.
     *
     * <p>Une sequence est atomique et ne bloque personne. La tentation
     * {@code count(*) + 1} produirait le meme numero pour deux commandes
     * simultanees, et la contrainte UNIQUE ferait echouer une commande deja
     * payee (voir V17).</p>
     */
    @Query(value = "SELECT nextval('commande_numero_seq')", nativeQuery = true)
    long prochainNumero();

    /**
     * Les commandes restees impayees au-dela du delai.
     *
     * <p>Alimente le travail periodique qui libere le stock reserve. Sans lui,
     * chaque paiement abandonne immobilise de la marchandise pour toujours
     * (chapitre 11).</p>
     */
    List<Commande> findByStatutAndDateCreationBefore(StatutCommande statut, Instant limite);
}
