package com.garah.api.surveillance.infra;

import com.garah.api.surveillance.domaine.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** L historique d un objet precis : « qui a touche a ce produit ? ». */
    List<AuditLog> findByEntiteAndEntiteIdOrderByDateHeureDesc(String entite, Long entiteId);

    /** L historique d une personne : « qu a fait ce responsable ? ». */
    Page<AuditLog> findByUtilisateurIdOrderByDateHeureDesc(Long utilisateurId, Pageable pagination);

    /**
     * Le journal entier, filtre par ce qu on cherche.
     *
     * <p>Les trois filtres sont FACULTATIFS et se combinent. Un {@code null}
     * desactive le sien : sans cela il faudrait huit methodes pour huit
     * combinaisons, et la neuvieme manquerait toujours.</p>
     *
     * <p>⚠️ {@code dateHeure DESC} puis {@code id DESC}. Deux lignes ecrites
     *    dans la meme milliseconde — ce qui arrive, une action en ecrit
     *    plusieurs — n auraient sinon pas d ordre stable : la meme ligne
     *    pourrait apparaitre sur deux pages, ou sur aucune.</p>
     */
    @Query("""
            SELECT a FROM AuditLog a
             WHERE (:action IS NULL OR a.action = :action)
               AND (:entite IS NULL OR a.entite = :entite)
               AND (:utilisateurId IS NULL OR a.utilisateurId = :utilisateurId)
             ORDER BY a.dateHeure DESC, a.id DESC
            """)
    Page<AuditLog> rechercher(@Param("action") String action,
                              @Param("entite") String entite,
                              @Param("utilisateurId") Long utilisateurId,
                              Pageable pagination);

    /** Les valeurs reellement presentes, pour proposer des filtres qui repondent. */
    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action")
    List<String> actionsConnues();

    @Query("SELECT DISTINCT a.entite FROM AuditLog a ORDER BY a.entite")
    List<String> entitesConnues();
}
