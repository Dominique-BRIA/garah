package com.garah.api.serviceclient.domaine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Une proposition de prix.
 *
 * <p>{@code utilisable} est calculé ici plutôt que laissé au frontend : la
 * règle « ni expirée, ni déjà consommée, ni refusée » est du métier. Trois
 * applications qui la réimplémenteraient chacune finiraient par diverger — et
 * l'une d'elles afficherait « Accepter » sur une proposition morte.</p>
 */
public record VueProposition(
        Long id,
        Long conversationId,
        Long varianteId,
        Long propositionParenteId,
        int quantite,
        BigDecimal prixUnitairePropose,
        Long auteurId,
        String sens,
        String statut,
        boolean utilisable,
        Instant dateCreation,
        Instant dateExpiration) {

    public static VueProposition de(PropositionPrix p) {
        return new VueProposition(p.getId(), p.getConversationId(), p.getVarianteId(),
                p.getPropositionParenteId(), p.getQuantite(), p.getPrixUnitairePropose(),
                p.getAuteurId(), p.getSens().name(), p.getStatut().name(),
                p.estUtilisable(), p.getDateCreation(), p.getDateExpiration());
    }
}
