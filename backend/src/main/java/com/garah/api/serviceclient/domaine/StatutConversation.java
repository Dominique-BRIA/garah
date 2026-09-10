package com.garah.api.serviceclient.domaine;

/**
 * Le cycle de vie d une conversation.
 *
 * <pre>
 *   INFORMATION ──le client repond──▶ WAITING ──prise──▶ ASSIGNED ──cloture──▶ CLOSED
 *        │                              ▲                   │
 *        │                              └── retrait Admin ──┘
 *        └──────────── un conseiller ecrit ──────────────▶ ASSIGNED
 * </pre>
 *
 * <p>La contrainte {@code conversation_responsable_coherent} lie ce statut au
 * responsable : WAITING impose un responsable nul, ASSIGNED et CLOSED en
 * exigent un. L incoherence la plus banale du module — une conversation
 * « prise » par personne — est donc impossible a ecrire.</p>
 */
public enum StatutConversation {

    /**
     * Ouverte par le SYSTEME pour annoncer quelque chose — un colis parti.
     *
     * <p>🎯 Personne n attend rien : ni le client, qui a recu l information,
     * ni l equipe, a qui rien n a ete demande. C est ce qui la distingue de
     * {@code WAITING}, qui veut dire « un client attend une reponse ».</p>
     *
     * <p>⚠️ La creer WAITING l aurait mise dans la file de l equipe : des
     * agents auraient ouvert des dossiers ou il n y a rien a faire, et
     * l alerte « Des clients attendent » aurait sonne pour des annonces.</p>
     *
     * <p>Des que le client y repond, elle devient {@code WAITING} : a partir
     * de la, quelqu un attend vraiment.</p>
     */
    INFORMATION,

    WAITING,
    ASSIGNED,
    CLOSED
}
