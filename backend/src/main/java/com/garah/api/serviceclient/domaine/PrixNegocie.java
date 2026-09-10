package com.garah.api.serviceclient.domaine;

import java.math.BigDecimal;

/**
 * Un prix accepté dans une discussion, et encore utilisable.
 *
 * <p>Porte l'identifiant de la proposition : la commande qui l'utilise doit
 * pouvoir dire d'où vient son prix, et la proposition ne sert qu'une fois.</p>
 */
public record PrixNegocie(Long propositionId, BigDecimal prixUnitaire) {
}
