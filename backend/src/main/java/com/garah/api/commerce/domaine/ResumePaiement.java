package com.garah.api.commerce.domaine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un paiement tel qu'on l'expose. <b>Pas l'entité.</b>
 *
 * <p>La règle d'architecture du chapitre 06 l'impose, et ArchUnit la vérifie :
 * exposer {@link Paiement} publierait le schéma de la base, et un renommage de
 * colonne casserait les trois frontends.</p>
 *
 * <p>Ici le risque est plus concret encore : {@code origineType} et
 * {@code origineId} désignent la réclamation ou le retour qui justifie un
 * remboursement. Ce sont des informations internes — elles n'ont rien à faire
 * dans une réponse lue par le client.</p>
 */
public record ResumePaiement(
        Long id,
        Long commandeId,
        String type,
        BigDecimal montant,
        String devise,
        String moyen,
        String statut,
        String referenceTransaction,
        Instant dateInitiation,
        Instant dateConfirmation) {

    public static ResumePaiement de(Paiement p) {
        return new ResumePaiement(
                p.getId(), p.getCommandeId(), p.getType().name(),
                p.getMontant(), p.getDevise(), p.getMoyen().name(),
                p.getStatut().name(), p.getReferenceTransaction(),
                p.getDateInitiation(), p.getDateConfirmation());
    }
}
