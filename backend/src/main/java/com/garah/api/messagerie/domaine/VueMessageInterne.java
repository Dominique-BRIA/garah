package com.garah.api.messagerie.domaine;

import java.time.Instant;

public record VueMessageInterne(
        Long id,
        Long filId,
        Long expediteurId,
        String contenu,
        Instant dateLecture,
        Instant dateEnvoi) {

    public static VueMessageInterne de(MessageInterne m) {
        return new VueMessageInterne(m.getId(), m.getFilId(), m.getExpediteurId(),
                m.getContenu(), m.getDateLecture(), m.getDateEnvoi());
    }
}
