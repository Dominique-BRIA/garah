package com.garah.api.marchand.domaine;

import java.time.Instant;

/** Un marchand tel qu'on l'expose. Pas l'entite (chapitre 06). */
public record VueMarchand(
        Long id,
        String code,
        String nom,
        String type,
        String telephone,
        String email,
        String statut,
        Instant dateCreation) {

    public static VueMarchand de(Marchand m) {
        return new VueMarchand(m.getId(), m.getCode(), m.getNom(), m.getType().name(),
                m.getTelephone(), m.getEmail(), m.getStatut().name(), m.getDateCreation());
    }
}
