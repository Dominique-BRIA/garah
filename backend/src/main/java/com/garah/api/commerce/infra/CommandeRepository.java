package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.Commande;
import com.garah.api.commerce.domaine.NumeroCommande;
import com.garah.api.commerce.domaine.StatutCommande;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * La liste du back-office : toutes les commandes, filtrables.
     *
     * <p>Distincte de {@link #findByClientIdOrderByDateCreationDesc} : celle-ci
     * repond a « ou en sont MES commandes ? », celle-la a « qu'est-ce qui
     * attend une action ? ». Melanger les deux derriere un parametre
     * obligerait chaque appelant a penser au filtre — et celui qui l'oublie
     * montrerait a un client les commandes de tout le monde.</p>
     *
     * <p>Le {@code LEFT JOIN FETCH} sur les lignes sert a compter les articles
     * sans une requete par commande. ⚠️ Il impose en revanche a Hibernate de
     * paginer <b>en memoire</b> : acceptable a vingt-cinq par page, a
     * surveiller si la taille grandit.</p>
     */
    @Query(value = """
            SELECT DISTINCT c FROM Commande c
              LEFT JOIN FETCH c.lignes
             WHERE (:statut IS NULL OR c.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(c.numero) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
             ORDER BY c.dateCreation DESC
            """,
            countQuery = """
            SELECT count(c) FROM Commande c
             WHERE (:statut IS NULL OR c.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(c.numero) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
            """)
    Page<Commande> administration(@Param("statut") StatutCommande statut,
                                  @Param("recherche") String recherche,
                                  Pageable pagination);

    /**
     * Les numeros de plusieurs commandes, en <b>une</b> requete.
     *
     * <p>Sert a la liste des paiements : un paiement ne porte qu un
     * {@code commandeId}, et afficher un identifiant numerique obligerait a
     * ouvrir chaque ligne pour savoir de quelle commande il s agit.</p>
     */
    @Query("""
            SELECT new com.garah.api.commerce.domaine.NumeroCommande(c.id, c.numero)
              FROM Commande c WHERE c.id IN :ids
            """)
    List<NumeroCommande> numerosPar(@Param("ids") Collection<Long> ids);
}
