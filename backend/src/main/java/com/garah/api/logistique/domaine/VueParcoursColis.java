package com.garah.api.logistique.domaine;

import java.math.BigDecimal;
import java.util.List;

/**
 * Un colis et son parcours DATE.
 *
 * <p>🎯 C'est ce qui distingue un suivi d'un statut. « EN_TRANSIT » ne dit pas
 * ou ; « receptionne a Bertoua le 12/03 a 14 h par David » le dit — et reste
 * vrai meme quand le colis est reparti.</p>
 *
 * <p>C'est la premiere regle fondatrice du projet : un statut est une photo,
 * un evenement est un fait. Une colonne `statut` ecrasee perd le parcours des
 * que le colis avance ; les evenements, eux, s'empilent et ne s'effacent
 * jamais.</p>
 */
public record VueParcoursColis(
        Long id,
        String numeroSuivi,
        BigDecimal poidsKg,
        String statut,
        List<VueEvenement> evenements) {
}
