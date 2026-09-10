package com.garah.api.serviceclient.domaine;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pousse un message de conversation aux deux bouts, en direct.
 *
 * <h2>🎯 Il fallait recharger la page pour voir arriver une réponse</h2>
 *
 * <p>Le client écrivait, l'agent ne voyait rien ; l'agent répondait, le client
 * ne voyait rien. Chacun rafraîchissait pour savoir si l'autre avait parlé —
 * ce qui, sur une conversation vive, revient à recharger toutes les vingt
 * secondes.</p>
 *
 * <p>⚠️ Le WebSocket existait DÉJÀ, et servait uniquement la messagerie
 * interne. Rien à installer : la même configuration, le même point d'entrée,
 * la même authentification sur la trame CONNECT.</p>
 *
 * <h2>⚠️ APRÈS la validation de la transaction, jamais pendant</h2>
 *
 * <p>{@code AFTER_COMMIT} n'est pas un détail de style. Diffuser pendant la
 * transaction montrerait le message à l'écran d'en face, puis la transaction
 * pourrait échouer : l'autre aurait lu quelque chose qui n'existe pas en base,
 * et qui disparaîtrait à son prochain rafraîchissement. Ce message fantôme est
 * impossible à expliquer à celui qui l'a vu.</p>
 *
 * <h2>⚠️ Un échec ici ne remonte JAMAIS</h2>
 *
 * <p>Le destinataire peut ne pas être connecté, le courtier peut être tombé.
 * Le message, lui, est enregistré : il sera lu au prochain chargement. Faire
 * échouer l'envoi pour une diffusion ratée ferait réécrire son message à
 * l'expéditeur, qui le croirait perdu — et il partirait deux fois.</p>
 *
 * <h2>⚠️ Des files PERSONNELLES, jamais un sujet partagé</h2>
 *
 * <p>Une destination commune comme {@code /sujet/conversations} serait livrée
 * à tous les connectés — <b>y compris les clients de la boutique</b>, qui
 * ouvrent le même WebSocket. Les conversations des uns arriveraient chez les
 * autres.</p>
 *
 * <p>D'où la limite assumée : quand personne n'a encore pris la conversation,
 * <b>rien n'est poussé</b>. Il n'y a pas de destinataire — n'importe quel
 * agent peut la prendre. C'est la notification qui prévient l'équipe, et la
 * liste qui se recharge à l'ouverture de l'écran.</p>
 */
@Component
public class DiffuseurConversation {

    /**
     * La destination, vue du client :
     * <pre>/utilisateur/file/conversations</pre>
     * Spring résout le préfixe vers la session de l'abonné.
     */
    public static final String DESTINATION = "/file/conversations";

    private final SimpMessagingTemplate diffusion;

    public DiffuseurConversation(SimpMessagingTemplate diffusion) {
        this.diffusion = diffusion;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surMessage(EvenementsConversation.MessageDansConversation e) {
        // ⚠️ On pousse AUX DEUX, y compris à l'expéditeur. Ce n'est pas un
        //    doublon inutile : quelqu'un peut avoir la même conversation
        //    ouverte sur son téléphone et sur son navigateur, et le second
        //    écran doit suivre le premier. C'est au client d'ignorer un
        //    message qu'il a déjà affiché — il connaît son identifiant.
        pousser(e.clientId(), e);
        pousser(e.responsableId(), e);
    }

    private void pousser(Long destinataireId, EvenementsConversation.MessageDansConversation e) {
        if (destinataireId == null) {
            return;
        }
        try {
            diffusion.convertAndSendToUser(
                    // Le sujet du jeton, donc le nom sous lequel la session
                    // WebSocket est enregistrée.
                    String.valueOf(destinataireId),
                    DESTINATION,
                    e.message());
        } catch (Exception ignore) {
            // Volontairement avalé : voir la javadoc de la classe.
        }
    }
}
