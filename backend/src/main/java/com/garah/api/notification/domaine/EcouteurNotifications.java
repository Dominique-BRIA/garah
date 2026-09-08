package com.garah.api.notification.domaine;

import com.garah.api.messagerie.domaine.MessageInterneEnvoye;
import com.garah.api.serviceclient.domaine.EvenementsConversation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * Ce qui déclenche une notification, et rien d'autre.
 *
 * <h2>🎯 Tout est ici, en un seul endroit</h2>
 *
 * <p>La liste des moments qui méritent de faire vibrer un téléphone est une
 * <b>décision de produit</b>, pas un détail d'implémentation. Éparpillée dans
 * les services, elle grossirait d'un cas à chaque fonctionnalité — et personne
 * ne pourrait plus répondre à « pourquoi ai-je reçu ça ? ».</p>
 *
 * <h2>⚠️ Le sens de la dépendance</h2>
 *
 * <p>{@code notification} écoute {@code serviceclient} et {@code messagerie},
 * et <b>jamais l'inverse</b>. Un appel direct depuis ces modules aurait fait
 * dépendre les deux l'un de l'autre, et le test de cycles aurait refusé la
 * compilation. C'est aussi ce qui garantit qu'une panne de Firebase
 * n'empêche pas d'enregistrer un message.</p>
 *
 * <h2>⚠️ APRÈS la validation de la transaction</h2>
 *
 * <p>Notifier pendant enverrait « un conseiller vous a répondu » pour un
 * message que la transaction peut encore annuler. Le client ouvrirait
 * l'application sur une conversation inchangée, et conclurait que
 * l'application ment.</p>
 */
@Component
public class EcouteurNotifications {

    /**
     * Qui reçoit le signal d'équipe.
     *
     * <p>« Une conversation attend » ne s'adresse à personne en particulier,
     * mais à quiconque peut la prendre. On le résout par la <b>permission</b>,
     * qui est la seule définition d'un rôle dans ce projet.</p>
     */
    private static final String PERMISSION_FILE_ATTENTE = "CONVERSATION_PRENDRE";

    private final ServiceNotifications notifications;
    private final DestinatairesParRole roles;

    public EcouteurNotifications(ServiceNotifications notifications,
                                 DestinatairesParRole roles) {
        this.notifications = notifications;
        this.roles = roles;
    }

    // -------------------------------------------------------------------------
    // Vers l'équipe
    // -------------------------------------------------------------------------

    /**
     * Un client vient d'ouvrir une conversation — souvent pour négocier.
     *
     * <p>Elle n'a aucun propriétaire : le message part à <b>tous ceux qui
     * peuvent la prendre</b>. Attendre qu'un responsable s'en saisisse pour
     * prévenir serait exactement à l'envers.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surConversationOuverte(EvenementsConversation.ConversationOuverte e) {
        notifications.prevenirTous(
                roles.ayantLaPermission(PERMISSION_FILE_ATTENTE),
                "Un client attend",
                e.sujet(),
                Map.of("type", "CONVERSATION", "id", String.valueOf(e.conversationId())));
    }

    // -------------------------------------------------------------------------
    // Vers une personne
    // -------------------------------------------------------------------------

    /**
     * Un message dans une conversation.
     *
     * <p>Deux destinataires possibles, et le sens décide :</p>
     *
     * <ul>
     *   <li>un conseiller répond → le <b>client</b> est prévenu ;</li>
     *   <li>le client écrit → le <b>responsable qui suit le dossier</b> est
     *       prévenu. S'il n'y en a pas encore, c'est l'équipe : un message qui
     *       n'arrive à personne est un client qui attend pour rien.</li>
     * </ul>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surMessage(EvenementsConversation.MessageDansConversation e) {
        Map<String, String> donnees =
                Map.of("type", "CONVERSATION", "id", String.valueOf(e.conversationId()));

        if (e.versLeClient()) {
            notifications.prevenir(e.clientId(), "Réponse de GARAH", e.extrait(), donnees);
            return;
        }

        if (e.responsableId() != null) {
            notifications.prevenir(e.responsableId(), "Message d'un client", e.extrait(), donnees);
        } else {
            notifications.prevenirTous(
                    roles.ayantLaPermission(PERMISSION_FILE_ATTENTE),
                    "Message d'un client",
                    e.extrait(),
                    donnees);
        }
    }

    /**
     * Une proposition de prix.
     *
     * <p>⚠️ C'est un <b>engagement daté</b> : il expire. Le manquer coûte une
     * vente, ce qui la distingue d'un simple message et justifie de faire
     * vibrer un téléphone.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surProposition(EvenementsConversation.PropositionDePrix e) {
        String titre = e.acceptee() ? "Prix accepté" : "Nouvelle proposition de prix";
        String corps = e.acceptee()
                ? "Le prix négocié est fixé. Vous pouvez commander."
                : "Un prix vous est proposé. Il a une date limite.";

        Map<String, String> donnees =
                Map.of("type", "NEGOCIATION", "id", String.valueOf(e.conversationId()));

        if (e.versLeClient()) {
            notifications.prevenir(e.clientId(), titre, corps, donnees);
        } else if (e.responsableId() != null) {
            notifications.prevenir(e.responsableId(), titre,
                    "Un client a répondu à votre proposition.", donnees);
        }
    }

    /**
     * Un message entre collègues.
     *
     * <p>Le destinataire est connu, et le blocage a déjà été vérifié à
     * l'envoi : un message bloqué n'existe pas, donc ne notifie rien.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surMessageInterne(MessageInterneEnvoye e) {
        notifications.prevenir(
                e.destinataireId(),
                "Message d'un collègue",
                extrait(e.contenu()),
                Map.of("type", "MESSAGE_INTERNE", "id", String.valueOf(e.filId())));
    }

    /**
     * Prévenir de la marchandise arrivée.
     *
     * <p>🎯 <b>Le moment qui compte le plus.</b> C'est là qu'un code de retrait
     * existe, et c'est la seule notification qu'on ne peut pas se permettre de
     * rater : sans elle, la marchandise attend au comptoir et le client se
     * demande où elle est.</p>
     *
     * <p>⚠️ Le code lui-même n'est <b>jamais</b> dans la notification. Une
     * bannière s'affiche sur un écran verrouillé, à la vue de qui passe : le
     * code suffit à emporter la marchandise. On dit qu'elle est arrivée, on ne
     * dit pas comment la prendre.</p>
     */
    public void marchandiseArrivee(Long clientId, Long commandeId, String pointRetrait) {
        notifications.prevenir(
                clientId,
                "Votre commande est arrivée",
                pointRetrait == null
                        ? "Elle vous attend à votre point de récupération."
                        : "Elle vous attend à " + pointRetrait + ".",
                Map.of("type", "COMMANDE", "id", String.valueOf(commandeId)));
    }

    /** Le signal d'équipe pour ce qui attend depuis trop longtemps. */
    public void fileDAttenteEnRetard(int combien) {
        List<Long> destinataires = roles.ayantLaPermission(PERMISSION_FILE_ATTENTE);
        if (destinataires.isEmpty() || combien == 0) {
            return;
        }
        notifications.prevenirTous(
                destinataires,
                "Des clients attendent",
                combien + " conversation(s) sans réponse depuis trop longtemps.",
                Map.of("type", "FILE_ATTENTE", "id", ""));
    }

    private static String extrait(String contenu) {
        String propre = contenu.strip().replaceAll("\\s+", " ");
        return propre.length() <= 120 ? propre : propre.substring(0, 117) + "…";
    }
}
