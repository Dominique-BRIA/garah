package com.garah.api.logistique.infra;

import com.garah.api.logistique.domaine.Expedition;
import com.garah.api.logistique.domaine.ResumeExpedition;
import com.garah.api.logistique.domaine.StatutExpedition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExpeditionRepository extends JpaRepository<Expedition, Long> {

    Optional<Expedition> findByNumero(String numero);

    List<Expedition> findByCommandeId(Long commandeId);

    @Query("""
            SELECT DISTINCT e FROM Expedition e
              LEFT JOIN FETCH e.colis
             WHERE e.id = :id
            """)
    Optional<Expedition> chargerAvecColis(Long id);

    @Query(value = "SELECT nextval('expedition_numero_seq')", nativeQuery = true)
    long prochainNumero();

    /**
     * La liste du back-office.
     *
     * <h2>Pourquoi {@code Commande} apparait dans une requete de logistique</h2>
     *
     * <p>Le domaine <b>commerce depend deja de logistique</b> : une commande
     * doit valider son point de recuperation. Appeler commerce depuis ici
     * formerait un CYCLE entre les deux domaines, et {@code ArchitectureTest}
     * refuserait le build.</p>
     *
     * <p>La requete, elle, ne cree aucune dependance de paquetage : le nom
     * {@code Commande} n'apparait que dans du HQL, jamais dans un {@code
     * import}. C'est le meme arbitrage que {@code ProduitRepository} pour le
     * stock, et il tient a un seul endroit — ici.</p>
     *
     * <p>Les deux jointures sont des {@code LEFT} : une commande supprimee ou
     * un point efface ne doivent pas faire DISPARAITRE l'expedition de la
     * liste. Elle decrit un mouvement physique qui a bien eu lieu.</p>
     */
    @Query(value = """
            SELECT new com.garah.api.logistique.domaine.ResumeExpedition(
                       e.id, e.numero, e.commandeId, c.numero, e.statut,
                       l.nom, l.ville,
                       (SELECT count(k) FROM Colis k WHERE k.expedition.id = e.id),
                       e.dateCreation, e.dateExpedition)
              FROM Expedition e
              LEFT JOIN Commande c ON c.id = e.commandeId
              LEFT JOIN Lieu l ON l.id = e.pointRecuperationId
             WHERE (:statut IS NULL OR e.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(e.numero) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                    OR LOWER(c.numero) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
             ORDER BY e.dateCreation DESC
            """,
            countQuery = """
            SELECT count(e) FROM Expedition e
              LEFT JOIN Commande c ON c.id = e.commandeId
             WHERE (:statut IS NULL OR e.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(e.numero) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                    OR LOWER(c.numero) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
            """)
    Page<ResumeExpedition> administration(@Param("statut") StatutExpedition statut,
                                          @Param("recherche") String recherche,
                                          Pageable pagination);

    /**
     * A quel client cette expedition est-elle destinee ?
     *
     * <p>🎯 <b>La reponse se DEDUIT, elle ne se saisit pas.</b> Le back-office
     * ne connait pas l identifiant du client en preparant un retrait, et le
     * lui faire saisir serait pire que de le deduire : une faute de frappe
     * preparerait le retrait de quelqu un d autre — et le code partirait au
     * mauvais destinataire.</p>
     *
     * <p>Meme arbitrage que {@link #administration} : {@code Commande}
     * n apparait qu en HQL, jamais dans un {@code import}, donc aucun cycle
     * entre les domaines.</p>
     */
    @Query("""
            SELECT c.clientId FROM Expedition e
              JOIN Commande c ON c.id = e.commandeId
             WHERE e.id = :expeditionId
            """)
    Optional<Long> clientDe(@Param("expeditionId") Long expeditionId);
}
