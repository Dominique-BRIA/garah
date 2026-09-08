package com.garah.api.messagerie.domaine;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pousse un message interne à son destinataire, en direct.
 *
 * <h2>⚠️ APRÈS la validation de la transaction, jamais pendant</h2>
 *
 * <p>{@code AFTER_COMMIT} n'est pas un détail de style. Diffuser pendant la
 * transaction enverrait le message à l'écran d'en face, puis la transaction
 * pourrait échouer : le destinataire aurait lu quelque chose qui n'existe pas
 * en base, et qui disparaîtrait à son prochain rafraîchissement. Ce genre de
 * message fantôme est impossible à expliquer à celui qui l'a vu.</p>
 *
 * <h2>⚠️ Un échec ici ne remonte JAMAIS</h2>
 *
 * <p>Le WebSocket peut être tombé, le destinataire peut ne pas être connecté.
 * Le message, lui, est enregistré : il sera lu au prochain chargement. Faire
 * échouer l'envoi pour une diffusion ratée ferait réécrire son message à
 * l'expéditeur, qui le croirait perdu — et il partirait deux fois.</p>
 */
@Component
public class DiffuseurMessagerie {

    /**
     * La destination, vue du client :
     * <pre>/utilisateur/file/messages-internes</pre>
     * Spring résout le préfixe vers la session de l'abonné.
     */
    public static final String DESTINATION = "/file/messages-internes";

    private final SimpMessagingTemplate diffusion;

    public DiffuseurMessagerie(SimpMessagingTemplate diffusion) {
        this.diffusion = diffusion;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surMessage(MessageInterneEnvoye evenement) {
        try {
            diffusion.convertAndSendToUser(
                    // L'identifiant de l'utilisateur — qui est aussi celui du
                    // responsable, la table partageant sa clé primaire. C'est
                    // le sujet du jeton, donc le nom sous lequel la session
                    // WebSocket est enregistrée.
                    String.valueOf(evenement.destinataireId()),
                    DESTINATION,
                    evenement);
        } catch (Exception ignore) {
            // Volontairement avalé : voir la javadoc de la classe.
        }
    }
}
