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
        Long prisPar,
        String prisParNom,
        Long closPar,
        String closParNom,
        String sujet,
        String statut,
        Instant dateCreation,
        Instant dateAffectation,
        Instant dateCloture,
        List<VueMessage> messages) {

    /**
     * ⚠️ Les NOMS sont facultatifs : les routes qui n'ont pas de quoi les
     * résoudre passent {@code null}. Un identifiant nu à l'écran ne dit rien à
     * personne, mais un écran vide ne dit rien non plus — mieux vaut le
     * numéro que rien, et le nom quand on l'a.
     */
    public static VueConversation resume(Conversation c) {
        return resume(c, null, null);
    }

    public static VueConversation resume(Conversation c, String prisParNom, String closParNom) {
        return new VueConversation(c.getId(), c.getClientId(),
                c.getPrisPar(), prisParNom, c.getClosPar(), closParNom,
                c.getSujet(), c.getStatut().name(), c.getDateCreation(),
                c.getDateAffectation(), c.getDateCloture(), List.of());
    }

    public static VueConversation complete(Conversation c) {
        return complete(c, null, null);
    }

    public static VueConversation complete(Conversation c, String prisParNom, String closParNom) {
        return new VueConversation(c.getId(), c.getClientId(),
                c.getPrisPar(), prisParNom, c.getClosPar(), closParNom,
                c.getSujet(), c.getStatut().name(), c.getDateCreation(),
                c.getDateAffectation(), c.getDateCloture(),
                c.getMessages().stream().map(VueMessage::de).toList());
    }
}
