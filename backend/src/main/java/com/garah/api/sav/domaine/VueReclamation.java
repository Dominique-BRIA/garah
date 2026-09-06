package com.garah.api.sav.domaine;

import java.time.Instant;

/** Une réclamation client. */
public record VueReclamation(
        Long id,
        String numero,
        Long clientId,
        Long commandeId,
        Long responsableId,
        String motif,
        String description,
        String statut,
        Instant dateCreation,
        Instant dateResolution) {

    public static VueReclamation de(Reclamation r) {
        return new VueReclamation(r.getId(), r.getNumero(), r.getClientId(),
                r.getCommandeId(), r.getResponsableId(), r.getMotif(),
                r.getDescription(), r.getStatut().name(),
                r.getDateCreation(), r.getDateResolution());
    }
}
