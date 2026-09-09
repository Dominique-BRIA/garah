package com.garah.api.serviceclient.domaine;

import com.garah.api.catalogue.domaine.ServiceTarification;
import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.serviceclient.infra.ConversationRepository;
import com.garah.api.serviceclient.infra.PropositionPrixRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * La négociation de prix.
 *
 * <p>Le fil complet est reconstituable : chaque contre-proposition pointe vers
 * celle à laquelle elle répond. C'est ce qui permet de répondre à « qui a
 * accordé cette remise, quand, et à partir de quel prix ? » — une question de
 * contrôle interne, pas de curiosité.</p>
 */
@Service
public class ServiceNegociation {

    private final PropositionPrixRepository propositions;
    private final ConversationRepository conversations;
    private final ServiceTarification tarification;

    /**
     * ⚠️ Seules les propositions de la MAISON entrent au journal.
     *
     * <p>Un client qui propose un prix ne fait que demander ; l'écouteur écarte
     * les acteurs externes, et c'est voulu. Un conseiller qui accorde une
     * remise, lui, décide de ce que l'entreprise encaisse — et rien d'autre
     * dans le système ne dit qui l'a accordée.</p>
     */
    private final JournalActions journal;

    public ServiceNegociation(PropositionPrixRepository propositions,
                              ConversationRepository conversations,
                              ServiceTarification tarification,
                              JournalActions journal) {
        this.journal = journal;
        this.propositions = propositions;
        this.conversations = conversations;
        this.tarification = tarification;
    }

    /**
     * Émet une proposition.
     *
     * <p>Le prix proposé par un responsable doit rester <b>inférieur ou égal</b>
     * au tarif public : une « négociation » qui augmente le prix serait au
     * mieux une erreur de saisie, au pire un abus. Le tarif public reste la
     * référence.</p>
     */
    @Transactional
    public PropositionPrix proposer(Long conversationId, Long varianteId, int quantite,
                                    BigDecimal prixPropose, Long auteurId,
                                    SensProposition sens, Duration validite) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> RessourceIntrouvable.de("Conversation", conversationId));

        if (conversation.estFermee()) {
            throw new ConflitEtat("CONVERSATION_FERMEE",
                    "On ne peut plus négocier dans une conversation fermée.");
        }
        if (quantite < 1) {
            throw new RegleMetierViolee("QUANTITE_INVALIDE",
                    "Une proposition porte sur au moins une unité.");
        }
        if (prixPropose == null || prixPropose.signum() <= 0) {
            throw new RegleMetierViolee("PRIX_INVALIDE",
                    "Un prix proposé doit être strictement positif.");
        }

        BigDecimal tarifPublic = null;
        if (sens == SensProposition.RESPONSABLE) {
            tarifPublic = tarification.prixUnitaire(varianteId, quantite);
            if (prixPropose.compareTo(tarifPublic) > 0) {
                throw new RegleMetierViolee("PROPOSITION_SUPERIEURE_AU_TARIF",
                        "Le prix proposé (" + prixPropose + ") dépasse le tarif public ("
                        + tarifPublic + ").");
            }
        }

        PropositionPrix proposition = propositions.save(
                new PropositionPrix(conversationId, varianteId, quantite,
                        prixPropose, auteurId, sens, validite));

        // Le tarif public est recopié À CÔTÉ du prix accordé : sans lui, la
        // remise ne se lit pas — il faudrait retrouver quelle grille
        // s'appliquait ce jour-là, et la grille a pu changer depuis.
        journal.creation("REMISE_ACCORDER", "proposition_prix", proposition.getId(),
                JournalActions.cliche("conversation", conversationId,
                        "variante", varianteId, "quantite", quantite,
                        "prixAccorde", prixPropose, "tarifPublic", tarifPublic));

        return proposition;
    }

    /**
     * Répond à une proposition par une autre.
     *
     * <p>La proposition d'origine passe à {@code REFUSEE} : une contre-offre
     * <b>est</b> un refus. Les laisser toutes deux ouvertes permettrait au
     * client d'accepter l'ancienne après avoir vu la nouvelle.</p>
     */
    @Transactional
    public PropositionPrix contreProposer(Long propositionId, BigDecimal nouveauPrix,
                                          Long auteurId, SensProposition sens, Duration validite) {
        PropositionPrix precedente = charger(propositionId);
        exigerNegociable(precedente);

        precedente.refuser();

        PropositionPrix contre = proposer(precedente.getConversationId(),
                precedente.getVarianteId(), precedente.getQuantite(),
                nouveauPrix, auteurId, sens, validite);

        contre.rattacherA(precedente.getId());
        return contre;
    }

    /**
     * Accepte une proposition.
     *
     * <p>⚠️ Le contrôle d'expiration est <b>indispensable</b> : sans lui, un
     * client accepterait en octobre un prix proposé en mars. C'est l'invariant
     * I-33, et il ne peut pas être une contrainte SQL — il dépend de l'heure
     * courante, que {@code CHECK} ne sait pas lire (chapitre 05 §7).</p>
     */
    @Transactional
    public PropositionPrix accepter(Long propositionId) {
        PropositionPrix proposition = charger(propositionId);
        exigerNegociable(proposition);

        proposition.accepter();
        return proposition;
    }

    @Transactional
    public PropositionPrix refuser(Long propositionId) {
        PropositionPrix proposition = charger(propositionId);
        exigerNegociable(proposition);

        proposition.refuser();
        return proposition;
    }

    /**
     * Marque une proposition comme consommée par une commande.
     *
     * <p>Appelé au passage de commande. Une proposition ne sert
     * <b>qu'une fois</b> : sinon une remise accordée pour 20 pièces
     * s'appliquerait à toutes les commandes suivantes.</p>
     */
    @Transactional
    public PropositionPrix consommer(Long propositionId) {
        PropositionPrix proposition = charger(propositionId);

        if (!proposition.estUtilisable()) {
            throw new ConflitEtat("PROPOSITION_INUTILISABLE",
                    "Cette proposition n'est plus utilisable (statut "
                    + proposition.getStatut() + ").");
        }

        proposition.consommer();
        return proposition;
    }

    /**
     * Le travail périodique qui expire les propositions dépassées.
     *
     * <p>Sans lui, une proposition resterait {@code PROPOSEE} indéfiniment. Le
     * contrôle d'expiration d'{@code accepter} suffirait à protéger — mais
     * l'écran afficherait des dizaines de propositions « en cours » qui ne le
     * sont plus. Marquer l'état rend l'interface honnête.</p>
     *
     * @return le nombre de propositions expirées
     */
    @Transactional
    public int expirerLesDepassees() {
        List<PropositionPrix> depassees = propositions
                .findByStatutAndDateExpirationBefore(StatutProposition.PROPOSEE, Instant.now());

        depassees.forEach(PropositionPrix::expirer);
        return depassees.size();
    }

    @Transactional(readOnly = true)
    public List<PropositionPrix> fil(Long conversationId) {
        return propositions.findByConversationIdOrderByDateCreationAsc(conversationId);
    }

    private void exigerNegociable(PropositionPrix proposition) {
        if (proposition.getStatut() != StatutProposition.PROPOSEE) {
            throw new ConflitEtat("PROPOSITION_CLOSE",
                    "Cette proposition n'est plus en cours (statut "
                    + proposition.getStatut() + ").");
        }
        if (proposition.estExpiree()) {
            throw new ConflitEtat("PROPOSITION_EXPIREE",
                    "Cette proposition a expiré le " + proposition.getDateExpiration() + ".");
        }
    }

    private PropositionPrix charger(Long propositionId) {
        return propositions.findById(propositionId)
                .orElseThrow(() -> RessourceIntrouvable.de("Proposition de prix", propositionId));
    }

    /**
     * La conversation dans laquelle vit une proposition.
     *
     * <p>Sert au contrôle de propriété : les routes de proposition ne portent
     * qu'un identifiant de proposition, mais le droit d'y toucher se juge sur
     * la <b>conversation</b> qui la contient.</p>
     */
    @Transactional(readOnly = true)
    public Long conversationDe(Long propositionId) {
        return charger(propositionId).getConversationId();
    }

}
