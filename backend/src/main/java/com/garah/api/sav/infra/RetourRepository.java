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
}
