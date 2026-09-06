package com.garah.api.surveillance.infra;

import com.garah.api.surveillance.domaine.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** L historique d un objet precis : « qui a touche a ce produit ? ». */
    List<AuditLog> findByEntiteAndEntiteIdOrderByDateHeureDesc(String entite, Long entiteId);

    /** L historique d une personne : « qu a fait ce responsable ? ». */
    Page<AuditLog> findByUtilisateurIdOrderByDateHeureDesc(Long utilisateurId, Pageable pagination);
}
