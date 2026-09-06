package com.garah.api.serviceclient.domaine;

import java.time.Instant;
import java.util.List;

/**
 * Une conversation telle qu'on l'expose, messages compris.
 *
 * <p>Construite <b>pendant</b> la transaction : {@code getMessages()} est une
 * collection paresseuse, et {@code open-in-view} est à {@code false}. La lire
 * après coup lèverait un {@code LazyInitializationException} — à l'exécution
 * seulement, jamais à la compilation.</p>
 */
public record VueConversation(
        Long id,
        Long clientId,
        Long responsableId,
        String sujet,
        String statut,
        Instant dateCreation,
        Instant dateAffectation,
        Instant dateCloture,
        List<VueMessage> messages) {

    /** Sans les messages : pour les listes et les files d'attente. */
    public static VueConversation resume(Conversation c) {
        return new VueConversation(c.getId(), c.getClientId(), c.getResponsableId(),
                c.getSujet(), c.getStatut().name(), c.getDateCreation(),
                c.getDateAffectation(), c.getDateCloture(), List.of());
    }

    public static VueConversation complete(Conversation c) {
        return new VueConversation(c.getId(), c.getClientId(), c.getResponsableId(),
                c.getSujet(), c.getStatut().name(), c.getDateCreation(),
                c.getDateAffectation(), c.getDateCloture(),
                c.getMessages().stream().map(VueMessage::de).toList());
    }
}
