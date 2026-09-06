package com.garah.api.serviceclient.domaine;

/**
 * Le cycle de vie d une conversation.
 *
 * <pre>
 *   WAITING ──prise par un responsable──▶ ASSIGNED ──cloture──▶ CLOSED
 *      ▲                                     │
 *      └──────── retrait par un Admin ───────┘
 * </pre>
 *
 * <p>La contrainte {@code conversation_responsable_coherent} lie ce statut au
 * responsable : WAITING impose un responsable nul, ASSIGNED et CLOSED en
 * exigent un. L incoherence la plus banale du module — une conversation
 * « prise » par personne — est donc impossible a ecrire.</p>
 */
public enum StatutConversation {
    WAITING,
    ASSIGNED,
    CLOSED
}
