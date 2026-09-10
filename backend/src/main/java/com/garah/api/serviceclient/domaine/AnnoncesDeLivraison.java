package com.garah.api.serviceclient.domaine;

import com.garah.api.logistique.domaine.EvenementsExpedition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Ce que la logistique dit au client, écrit dans ses discussions.
 *
 * <h2>Le sens de la dépendance</h2>
 *
 * <p>{@code serviceclient} écoute {@code logistique}, jamais l'inverse. La
 * logistique n'a pas à savoir qu'une messagerie existe : le jour où elle
 * tombe, un colis doit quand même pouvoir partir.</p>
 *
 * <h2>⚠️ APRÈS la validation, et sans jamais faire échouer le départ</h2>
 *
 * <p>Annoncer pendant la transaction écrirait « votre colis est parti » pour
 * un départ que la transaction peut encore annuler. Et une annonce qui échoue
 * ne doit pas remonter à l'agent : il verrait une erreur sur un départ
 * pourtant enregistré, et le saisirait une seconde fois.</p>
 */
@Component
public class AnnoncesDeLivraison {

    private static final Logger log = LoggerFactory.getLogger(AnnoncesDeLivraison.class);

    private final ServiceConversation conversations;

    public AnnoncesDeLivraison(ServiceConversation conversations) {
        this.conversations = conversations;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surColisParti(EvenementsExpedition.ColisParti e) {
        try {
            conversations.annoncer(e.clientId(), e.commandeId(), """
                    Votre commande est partie.

                    Numéro de suivi : %s

                    Vous pouvez suivre son trajet depuis « Suivre un colis », \
                    ou depuis le détail de votre commande. Ce numéro peut se \
                    transmettre à la personne qui ira la retirer.""".formatted(e.numeroSuivi()));
        } catch (RuntimeException echec) {
            // ⚠️ Journalisé, jamais relancé : le colis EST parti. Le client
            //    garde le numéro dans le détail de sa commande, et la
            //    notification poussée part de son côté.
            log.warn("Annonce de depart non ecrite (commande {}, colis {}) : {}",
                    e.commandeId(), e.numeroSuivi(), echec.getMessage());
        }
    }
}
