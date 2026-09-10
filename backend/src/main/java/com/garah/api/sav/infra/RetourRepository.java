package com.garah.api.sav.infra;

import com.garah.api.sav.domaine.Retour;
import com.garah.api.sav.domaine.StatutRetour;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RetourRepository extends JpaRepository<Retour, Long> {

    Optional<Retour> findByNumero(String numero);

    List<Retour> findByCommandeId(Long commandeId);

    @Query("""
            SELECT DISTINCT r FROM Retour r
              LEFT JOIN FETCH r.lignes
             WHERE r.id = :id
            """)
    Optional<Retour> chargerAvecLignes(Long id);

    /**
     * Les retours d UN client, du plus recent au plus ancien.
     *
     * <p>⚠️ L ordre est l INVERSE de celui du back-office. La file de gestion
     * montre les plus anciens d abord — un retour qui traine est un client qui
     * attend son argent. Mais le client, lui, vient voir ce qu il a demande
     * HIER : le mettre en bas l obligerait a chercher.</p>
     */
    Page<Retour> findByClientIdOrderByDateCreationDesc(Long clientId, Pageable pagination);

    @Query(value = "SELECT nextval('retour_numero_seq')", nativeQuery = true)
    long prochainNumero();

    /**
     * La liste du back-office.
     *
     * <p>Sans filtre de statut, elle repond a « qu est-ce qui est en cours ? ».
     * Avec, elle repond a « qu est-ce qui attend MON geste ? » — accepter,
     * receptionner, valider ne sont pas le meme metier, et chacun a sa file.</p>
     *
     * <p>Les plus ANCIENS d abord, contrairement aux autres listes du projet.
     * Un retour qui traine est un client qui attend son argent : c est celui-la
     * qu il faut voir en haut, pas le dernier arrive.</p>
     */
    @Query("""
            SELECT r FROM Retour r
             WHERE (:statut IS NULL OR r.statut = :statut)
             ORDER BY r.dateCreation ASC
            """)
    Page<Retour> administration(@Param("statut") StatutRetour statut, Pageable pagination);

    /**
     * Ce que chaque retour annonce et ce qui a ete rendu, en UNE requete.
     *
     * <p>Une page de vingt-cinq retours demanderait sinon vingt-cinq requetes
     * pour compter leurs lignes — la regle du projet est une requete par page,
     * jamais une par ligne.</p>
     *
     * <p>Les deux chiffres ne disent pas la meme chose : le premier est
     * declare par le client, le second constate apres ouverture du colis.
     * Voir {@link com.garah.api.sav.domaine.ResumeRetour}.</p>
     */
    @Query("""
            SELECT l.retour.id, sum(l.quantite), sum(l.montantRembourse)
              FROM LigneRetour l
             WHERE l.retour.id IN :ids
             GROUP BY l.retour.id
            """)
    List<Object[]> totauxPar(@Param("ids") Collection<Long> ids);

    /**
     * Combien d'unités de cette ligne ont été REMISES au client.
     *
     * <p>Se lit dans les colis remis. En SQL natif : {@code sav} n'a pas à
     * dépendre de {@code logistique} pour une somme.</p>
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT COALESCE(SUM(lc.quantite), 0) FROM ligne_colis lc
              JOIN colis c ON c.id = lc.colis_id
             WHERE lc.ligne_commande_id = :ligneId AND c.statut = 'REMIS'
            """, nativeQuery = true)
    long quantiteRemise(@org.springframework.data.repository.query.Param("ligneId") Long ligneId);

    /**
     * Combien d'unités de cette ligne font déjà l'objet d'un retour.
     *
     * <p>⚠️ Même définition que le trigger I-40 : un retour REFUSÉ ne compte
     * pas — l'article est resté chez le client, il peut le redemander.</p>
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT COALESCE(SUM(lr.quantite), 0) FROM ligne_retour lr
              JOIN retour r ON r.id = lr.retour_id
             WHERE lr.ligne_commande_id = :ligneId AND r.statut <> 'REFUSE'
            """, nativeQuery = true)
    long quantiteDejaRetournee(@org.springframework.data.repository.query.Param("ligneId") Long ligneId);
}
