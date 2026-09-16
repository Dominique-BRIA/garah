package com.garah.api.serviceclient.domaine;

import com.garah.api.logistique.domaine.EvenementsExpedition;
import com.garah.api.logistique.domaine.RetraitMarchandise;
import com.garah.api.logistique.infra.RetraitMarchandiseRepository;
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

    /**
     * Pour lire le code de retrait, que l'événement ne porte pas.
     *
     * <p>🎯 C'est délibéré : le code ne voyage pas dans l'événement pour ne pas
     * finir sur une bannière de notification, à la vue de qui passe devant un
     * écran verrouillé. Une <b>discussion</b>, elle, est derrière
     * l'authentification — elle a le droit de le transmettre, alors elle va le
     * chercher.</p>
     */
    private final RetraitMarchandiseRepository retraits;

    public AnnoncesDeLivraison(ServiceConversation conversations,
                               RetraitMarchandiseRepository retraits) {
        this.conversations = conversations;
        this.retraits = retraits;
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

    /**
     * La marchandise est arrivée — et c'est ici que part le code de retrait.
     *
     * <h2>🎯 Pourquoi c'est l'annonce la plus importante du système</h2>
     *
     * <p>Le code de retrait est le <b>seul moyen</b> de prouver qu'un colis est
     * le sien. Sans lui, la marchandise reste au comptoir.</p>
     *
     * <p>Depuis D-53, un compte peut n'avoir ni adresse e-mail ni numéro — un
     * compte TikTok n'en a aucun des deux. La discussion devient alors son
     * <b>unique</b> canal : c'est là, et nulle part ailleurs, qu'il lira son
     * code.</p>
     *
     * <p>⚠️ Une discussion et non une notification, pour la raison qui vaut
     * déjà pour le numéro de suivi : une notification s'efface d'un geste et ne
     * se relit pas. Un code de retrait sert des jours plus tard, se recopie, et
     * se transmet à qui ira chercher le colis.</p>
     *
     * <p>⚠️ La notification poussée, elle, continue de ne dire <b>que</b>
     * l'arrivée — jamais le code. Une bannière s'affiche sur un écran
     * verrouillé, à la vue de qui passe.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surMarchandiseDisponible(EvenementsExpedition.MarchandiseDisponible e) {
        try {
            String code = retraits.findByExpeditionId(e.expeditionId())
                    .map(RetraitMarchandise::getCodeRetrait)
                    .orElse(null);

            // ⚠️ Sans code, on annonce quand même l'arrivée.
            //
            //    Le retrait vient d'être créé dans la transaction qui a publié
            //    cet événement : l'absence serait anormale. Mais renoncer à
            //    toute l'annonce pour un code manquant laisserait le client
            //    ignorer que son colis est arrivé — le pire des deux.
            String contenu = code == null
                    ? """
                      Votre commande est arrivée%s.

                      Présentez-vous au comptoir avec votre code de retrait, \
                      que vous trouverez dans le détail de votre commande."""
                            .formatted(ou(e.pointRecuperation()))
                    : """
                      Votre commande est arrivée%s.

                      Code de retrait : %s

                      Présentez ce code au comptoir : c'est lui qui prouve que \
                      le colis est le vôtre. Ne le communiquez à personne avant \
                      le retrait — quiconque le détient peut emporter la \
                      marchandise."""
                            .formatted(ou(e.pointRecuperation()), code);

            conversations.annoncer(e.clientId(), e.commandeId(), contenu);
        } catch (RuntimeException echec) {
            // Journalisé, jamais relancé : la marchandise EST disponible, et
            // l'agent ne doit pas voir d'erreur sur un retrait bien préparé.
            log.warn("Annonce d arrivee non ecrite (commande {}) : {}",
                    e.commandeId(), echec.getMessage());
        }
    }

    /** « à Bangui », ou rien si le point a disparu. */
    private static String ou(String pointRecuperation) {
        return pointRecuperation == null || pointRecuperation.isBlank()
                ? "" : " à " + pointRecuperation;
    }
}
