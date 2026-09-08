package com.garah.api.surveillance.domaine;

import java.time.Instant;

/**
 * Ce qu'une personne a fait — <b>sans le contenu de ce qu'elle a touché</b>.
 *
 * <h2>🎯 Pourquoi une seconde vue du même journal</h2>
 *
 * <p>{@link VueAudit} porte {@code ancienneValeur} et {@code nouvelleValeur} :
 * des clichés JSON d'entités modifiées, qui peuvent contenir <b>n'importe
 * quelle</b> donnée du système — le prix d'achat d'un marchand, l'adresse d'un
 * client, le téléphone d'un collègue. C'est pourquoi cette vue-là est réservée
 * au module SÉCURITÉ, donc au seul SuperAdmin.</p>
 *
 * <p>Un chef de service a besoin d'une autre réponse : « qu'a fait mon
 * équipier, et quand ? ». Lui donner {@code VueAudit} pour cela ouvrirait, par
 * ricochet, la lecture de tout le système à quiconque dirige un service — un
 * accès que personne n'aurait accordé s'il avait été demandé en ces termes.</p>
 *
 * <p>⚠️ Ce n'est pas un filtrage à l'écran. Les champs sensibles ne quittent
 * pas le serveur : un filtre côté interface ne serait qu'un rideau devant une
 * fenêtre ouverte.</p>
 */
public record VueActivite(
        Long id,
        String action,
        String entite,
        Long entiteId,
        Instant dateHeure) {

    static VueActivite de(AuditLog a) {
        return new VueActivite(a.getId(), a.getAction(), a.getEntite(),
                a.getEntiteId(), a.getDateHeure());
    }
}
