package com.garah.api.logistique.domaine;

import java.math.BigDecimal;

/**
 * Un lieu — point de récupération ou point de transit.
 *
 * <p>La liste des points de récupération est <b>publique</b> : le client doit
 * pouvoir choisir le sien avant même d'avoir un compte (D-05), avec les
 * horaires et les frais. C'est une information commerciale, pas interne.</p>
 */
public record VueLieu(
        Long id,
        String type,
        String nom,
        String pays,
        String ville,
        String adresse,
        String telephone,
        String horaires,
        BigDecimal fraisAcheminement,
        String statut) {

    public static VueLieu de(Lieu l) {
        return new VueLieu(l.getId(), l.getType().name(), l.getNom(), l.getPays(),
                l.getVille(), l.getAdresse(), l.getTelephone(), l.getHoraires(),
                l.getFraisAcheminement(), l.getStatut());
    }
}
