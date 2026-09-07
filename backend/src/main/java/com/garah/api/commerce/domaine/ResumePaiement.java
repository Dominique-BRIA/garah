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
        /** Renseigné dans les listes du back-office, nul ailleurs. */
        String commandeNumero,
        String type,
        BigDecimal montant,
        String devise,
        String moyen,
        String statut,
        String referenceTransaction,
        Instant dateInitiation,
        Instant dateConfirmation) {

    public static ResumePaiement de(Paiement p) {
        return de(p, null);
    }

    /**
     * @param numero le numéro de la commande, résolu <b>en amont pour toute la
     *               page</b>. Un paiement ne porte qu'un {@code commandeId} :
     *               afficher un identifiant numérique dans une liste
     *               obligerait à ouvrir chaque ligne pour savoir de quelle
     *               commande il s'agit.
     */
    public static ResumePaiement de(Paiement p, String numero) {
        return new ResumePaiement(
                p.getId(), p.getCommandeId(), numero, p.getType().name(),
                p.getMontant(), p.getDevise(), p.getMoyen().name(),
                p.getStatut().name(), p.getReferenceTransaction(),
                p.getDateInitiation(), p.getDateConfirmation());
    }
}
