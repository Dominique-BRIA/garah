package com.garah.api.surveillance.domaine;

import java.time.Instant;

/**
 * Une ligne du journal d'audit.
 *
 * <p>⚠️ {@code ancienneValeur} et {@code nouvelleValeur} contiennent des
 * clichés JSON d'entités modifiées. Ils peuvent donc porter <b>n'importe
 * quelle</b> donnée personnelle du système. L'accès à ces routes est réservé
 * à {@code AUDIT_CONSULTER}, qui n'appartient qu'au module SECURITE — donc au
 * seul SuperAdmin (chapitre 08).</p>
 */
public record VueAudit(
        Long id,
        Long utilisateurId,
        String acteurNom,
        String acteurEmail,
        String action,
        String entite,
        Long entiteId,
        String ancienneValeur,
        String nouvelleValeur,
        String adresseIp,
        Instant dateHeure) {

    public static VueAudit de(AuditLog a) {
        return new VueAudit(a.getId(), a.getUtilisateurId(), a.getActeurNom(),
                a.getActeurEmail(), a.getAction(), a.getEntite(), a.getEntiteId(),
                a.getAncienneValeur(), a.getNouvelleValeur(), a.getAdresseIp(),
                a.getDateHeure());
    }
}
