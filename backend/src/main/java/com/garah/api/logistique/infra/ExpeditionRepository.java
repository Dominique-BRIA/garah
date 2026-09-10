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

    /**
     * Ou le client de cette commande vient-il chercher sa marchandise ?
     *
     * <p>🎯 <b>Meme principe que {@link #clientDe} : ca se deduit.</b> Le
     * client a choisi son point de recuperation en commandant, et les frais
     * d acheminement de CE point ont ete figes sur la commande. Redemander la
     * destination a l operateur qui prepare l expedition lui donnerait le
     * moyen d envoyer la marchandise dans une autre ville que celle payee —
     * et rien, ensuite, ne rapprocherait les deux.</p>
     */
    @Query("SELECT c.pointRecuperationId FROM Commande c WHERE c.id = :commandeId")
    Optional<Long> destinationDe(@Param("commandeId") Long commandeId);

    /**
     * À qui appartient cette commande.
     *
     * <p>Même procédé que {@link #clientDe(Long)} : {@code Commande} est
     * nommée dans la requête et <b>jamais importée</b>. Une importation ferait
     * dépendre {@code logistique} de {@code commerce}, qui dépend déjà de
     * {@code logistique} — et le test de cycles refuserait la compilation.</p>
     *
     * <p>Elle sert à répondre « introuvable » sur la commande d'un autre
     * <b>avant</b> de regarder s'il existe un retrait : sans elle, une
     * commande légitime dont le retrait n'est pas encore préparé se
     * confondrait avec la commande de quelqu'un d'autre.</p>
     */
    @Query("SELECT c.clientId FROM Commande c WHERE c.id = :commandeId")
    Optional<Long> proprietaireDe(@Param("commandeId") Long commandeId);

    /**
     * Ce qui est deja parti pour une commande.
     *
     * <p>Meme projection que {@link #administration}, meme raison d y faire
     * figurer le point de recuperation : un identifiant nu obligerait a ouvrir
     * chaque ligne pour savoir de quoi il s agit.</p>
     */
    @Query("""
            SELECT new com.garah.api.logistique.domaine.ResumeExpedition(
                       e.id, e.numero, e.commandeId, c.numero, e.statut,
                       l.nom, l.ville,
                       (SELECT count(k) FROM Colis k WHERE k.expedition.id = e.id),
                       e.dateCreation, e.dateExpedition)
              FROM Expedition e
              LEFT JOIN Commande c ON c.id = e.commandeId
              LEFT JOIN Lieu l ON l.id = e.pointRecuperationId
             WHERE e.commandeId = :commandeId
             ORDER BY e.dateCreation DESC
            """)
    List<ResumeExpedition> resumesParCommande(@Param("commandeId") Long commandeId);

    /**
     * Le statut d'une commande, en texte.
     *
     * <p>⚠️ En SQL natif et en texte : {@code StatutCommande} vit dans
     * {@code commerce}, qui dépend déjà de {@code logistique}. L'importer
     * fermerait un cycle que le test d'architecture refuse.</p>
     */
    @Query(value = "SELECT statut FROM commande WHERE id = :commandeId", nativeQuery = true)
    Optional<String> statutDeCommande(@Param("commandeId") Long commandeId);

    /**
     * De quoi dire où en est la marchandise d'une commande, en UNE requête.
     *
     * <pre>
     * [0] expéditions
     * [1] lignes de commande pas entièrement mises en colis
     * [2] colis non vides jamais partis
     * [3] colis non vides pas encore au comptoir
     * [4] colis non vides pas encore remis
     * </pre>
     *
     * <p>« Parti » se lit dans les ÉVÉNEMENTS, pas dans le statut : un colis
     * bloqué avant tout départ est BLOQUE, exactement comme un colis bloqué
     * en route. Seul le journal distingue les deux.</p>
     */
    @Query(value = """
            SELECT
              (SELECT count(*) FROM expedition e WHERE e.commande_id = :commandeId),
              (SELECT count(*) FROM ligne_commande l
                WHERE l.commande_id = :commandeId
                  AND l.quantite > (SELECT COALESCE(SUM(lc.quantite), 0)
                                      FROM ligne_colis lc WHERE lc.ligne_commande_id = l.id)),
              (SELECT count(*) FROM colis c JOIN expedition e ON e.id = c.expedition_id
                WHERE e.commande_id = :commandeId
                  AND EXISTS (SELECT 1 FROM ligne_colis lc WHERE lc.colis_id = c.id)
                  AND NOT EXISTS (SELECT 1 FROM evenement_expedition ev
                                   WHERE ev.colis_id = c.id AND ev.type = 'DEPART')),
              (SELECT count(*) FROM colis c JOIN expedition e ON e.id = c.expedition_id
                WHERE e.commande_id = :commandeId
                  AND EXISTS (SELECT 1 FROM ligne_colis lc WHERE lc.colis_id = c.id)
                  AND c.statut NOT IN ('DISPONIBLE', 'REMIS')),
              (SELECT count(*) FROM colis c JOIN expedition e ON e.id = c.expedition_id
                WHERE e.commande_id = :commandeId
                  AND EXISTS (SELECT 1 FROM ligne_colis lc WHERE lc.colis_id = c.id)
                  AND c.statut <> 'REMIS')
            """, nativeQuery = true)
    List<Object[]> avancement(@Param("commandeId") Long commandeId);
}
