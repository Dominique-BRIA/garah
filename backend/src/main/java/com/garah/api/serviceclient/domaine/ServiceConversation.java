package com.garah.api.serviceclient.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.serviceclient.infra.AffectationConversationRepository;
import com.garah.api.serviceclient.infra.ConversationRepository;
import com.garah.api.serviceclient.infra.EvaluationConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Les conversations entre clients et responsables.
 *
 * <p>Le sujet technique de ce service est la <b>file d'attente partagée</b> :
 * plusieurs responsables voient la même liste de conversations en attente, et
 * le premier qui clique doit gagner — sans que les autres reçoivent une erreur
 * incompréhensible.</p>
 */
@Service
public class ServiceConversation {

    private final ConversationRepository conversations;
    private final AffectationConversationRepository affectations;
    private final EvaluationConversationRepository evaluations;

    public ServiceConversation(ConversationRepository conversations,
                               AffectationConversationRepository affectations,
                               EvaluationConversationRepository evaluations) {
        this.conversations = conversations;
        this.affectations = affectations;
        this.evaluations = evaluations;
    }

    @Transactional
    public Conversation ouvrir(Long clientId, String sujet, String premierMessage) {
        Conversation conversation = conversations.save(new Conversation(clientId, sujet));
        if (premierMessage != null && !premierMessage.isBlank()) {
            conversation.ajouterMessage(clientId, premierMessage);
        }
        return conversation;
    }

    /**
     * Un responsable prend une conversation en attente.
     *
     * <p><b>La technique diffère volontairement de celle du stock.</b></p>
     *
     * <pre>
     * Stock          verrou pessimiste     on a besoin de la valeur AVANT
     * Conversation   UPDATE conditionnel   on n'a besoin de rien
     * </pre>
     *
     * <p>Ici, une seule requête suffit :</p>
     *
     * <pre>UPDATE conversation SET … WHERE id = ? AND statut = 'WAITING'</pre>
     *
     * <p>Si elle modifie <b>une</b> ligne, on a gagné. Si elle en modifie
     * <b>zéro</b>, quelqu'un a été plus rapide. Pas de verrou, pas d'attente.</p>
     *
     * <p>Le message d'erreur compte : « un autre responsable a déjà pris cette
     * conversation » se comprend. « Erreur de mise à jour » ne se comprend
     * pas, et fait cliquer trois fois de plus.</p>
     */
    @Transactional
    public Conversation prendre(Long conversationId, Long responsableId) {
        // Sans cette garde, un responsable nul produirait une conversation
        // ASSIGNED sans responsable — que la contrainte
        // conversation_responsable_coherent refuse, avec un message
        // incompréhensible pour l'appelant.
        if (responsableId == null) {
            throw new RegleMetierViolee("RESPONSABLE_OBLIGATOIRE",
                    "Une conversation doit être prise par un responsable identifié.");
        }

        int lignes = conversations.prendre(conversationId, responsableId);

        if (lignes == 0) {
            conversations.findById(conversationId)
                    .orElseThrow(() -> RessourceIntrouvable.de("Conversation", conversationId));

            throw new ConflitEtat("CONVERSATION_DEJA_PRISE",
                    "Un autre responsable a déjà pris cette conversation.");
        }

        affectations.save(new AffectationConversation(conversationId, responsableId, null));
        return charger(conversationId);
    }

    /**
     * Un Admin retire une conversation à son responsable.
     *
     * <p>Le motif est <b>obligatoire</b> : retirer un dossier à quelqu'un est
     * une décision qui doit pouvoir s'expliquer, et elle sera relue lors de
     * l'évaluation du responsable (§20).</p>
     */
    @Transactional
    public Conversation reaffecter(Long conversationId, Long nouveauResponsableId,
                                   Long adminId, String motif) {
        if (motif == null || motif.isBlank()) {
            throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
                    "Un retrait de conversation doit être justifié.");
        }

        Conversation conversation = charger(conversationId);
        if (conversation.estFermee()) {
            throw new ConflitEtat("CONVERSATION_FERMEE",
                    "Une conversation fermée ne peut plus être réaffectée.");
        }

        // Clôturer l'affectation en cours AVANT d'en ouvrir une autre :
        // l'index unique partiel n'accepte qu'une affectation ouverte (I-30).
        affectations.findByConversationIdAndDateFinIsNull(conversationId)
                .ifPresent(a -> {
                    a.cloturer(motif);
                    affectations.saveAndFlush(a);
                });

        conversation.remettreEnAttente();
        conversations.saveAndFlush(conversation);

        if (nouveauResponsableId != null) {
            conversations.prendre(conversationId, nouveauResponsableId);
            affectations.save(new AffectationConversation(
                    conversationId, nouveauResponsableId, adminId));
        }

        return charger(conversationId);
    }

    @Transactional
    public Message repondre(Long conversationId, Long expediteurId, String contenu) {
        if (contenu == null || contenu.isBlank()) {
            throw new RegleMetierViolee("MESSAGE_VIDE", "Un message ne peut pas être vide.");
        }

        Conversation conversation = charger(conversationId);
        if (conversation.estFermee()) {
            throw new ConflitEtat("CONVERSATION_FERMEE",
                    "Cette conversation est fermée. Ouvrez-en une nouvelle.");
        }

        return conversation.ajouterMessage(expediteurId, contenu);
    }

    @Transactional
    public Conversation fermer(Long conversationId) {
        Conversation conversation = charger(conversationId);
        if (conversation.estFermee()) {
            return conversation;   // idempotent : fermer deux fois n'est pas une erreur
        }

        affectations.findByConversationIdAndDateFinIsNull(conversationId)
                .ifPresent(a -> a.cloturer("Conversation close"));

        conversation.fermer();
        return conversation;
    }

    /**
     * Le client évalue le service reçu.
     *
     * <p>Deux règles, et la première surprend : on ne peut évaluer qu'une
     * conversation <b>fermée</b>. Évaluer un échange en cours fausserait la
     * mesure de satisfaction de la §20 — le responsable n'a pas fini son
     * travail.</p>
     */
    @Transactional
    public EvaluationConversation evaluer(Long conversationId, int note, String commentaire) {
        Conversation conversation = charger(conversationId);

        if (!conversation.estFermee()) {
            throw new ConflitEtat("CONVERSATION_OUVERTE",
                    "Une conversation ne peut être évaluée qu'une fois close.");
        }
        if (note < 1 || note > 5) {
            throw new RegleMetierViolee("NOTE_INVALIDE", "La note doit être comprise entre 1 et 5.");
        }
        if (evaluations.findByConversationId(conversationId).isPresent()) {
            throw new ConflitEtat("DEJA_EVALUEE", "Cette conversation a déjà été évaluée.");
        }

        return evaluations.save(new EvaluationConversation(conversationId, note, commentaire));
    }

    @Transactional(readOnly = true)
    public List<Conversation> fileDAttente() {
        return conversations.findByStatutOrderByDateCreationAsc(StatutConversation.WAITING);
    }

    @Transactional(readOnly = true)
    public List<AffectationConversation> historique(Long conversationId) {
        return affectations.findByConversationIdOrderByDateDebutAsc(conversationId);
    }

    private Conversation charger(Long conversationId) {
        return conversations.findById(conversationId)
                .orElseThrow(() -> RessourceIntrouvable.de("Conversation", conversationId));
    }
}
