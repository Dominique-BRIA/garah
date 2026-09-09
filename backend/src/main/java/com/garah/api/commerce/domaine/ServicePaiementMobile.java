package com.garah.api.commerce.domaine;

import com.garah.api.commerce.infra.ClientCampay;
import com.garah.api.commerce.infra.CommandeRepository;
import com.garah.api.commerce.infra.PaiementRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Le paiement mobile money de bout en bout : notre domaine et l'opérateur.
 *
 * <p>{@link ServicePaiement} ne connaît que nos propres règles et ne parle à
 * personne. Cette classe-ci est la seule à faire dialoguer les deux mondes.
 * La séparation n'est pas cosmétique : les 10 tests de {@code ServicePaiement}
 * tournent sans réseau, et continueront de tourner le jour où Campay sera
 * remplacé.</p>
 *
 * <h2>Le cycle complet</h2>
 * <pre>
 * 1. demander()        notre Paiement INITIE → collecte Campay → EN_ATTENTE
 * 2. le client saisit son code sur son téléphone
 * 3a. Campay appelle notre webhook           → traiterNotification()
 * 3b. ou le webhook se perd                  → reconcilier() le rattrape
 * 4. ServicePaiement.confirmer()  → commande PAYEE, stock sorti, grand livre
 * </pre>
 *
 * <h2>🎯 Le modèle de sécurité du webhook</h2>
 *
 * <p>Une notification entrante est traitée comme <b>un signal, jamais comme une
 * information</b>. On en extrait une seule chose : la référence de transaction.
 * Le statut et le montant qu'elle annonce sont ignorés — puis <b>redemandés à
 * Campay</b> sur une connexion que nous ouvrons, avec nos identifiants.</p>
 *
 * <pre>
 * ce que le webhook apporte     « la transaction ABC a bougé »   ← non fiable
 * ce qui décide                 GET /transaction/ABC/            ← fiable
 * </pre>
 *
 * <p><b>Pourquoi ce choix plutôt qu'une vérification de signature.</b> Une
 * signature protège l'intégrité du message, mais toute la sécurité repose alors
 * sur l'exactitude de son algorithme, sur le secret partagé, et sur le fait
 * qu'aucun des deux n'a fuité. Ici, un inconnu qui appelle notre webhook avec
 * la référence de son choix n'obtient <b>rien</b> : il déclenche une question
 * dont il ne contrôle pas la réponse. La signature reste utile en défense
 * supplémentaire — voir {@code ControleurPaiement} — mais elle n'est pas ce qui
 * tient.</p>
 */
@Service
public class ServicePaiementMobile {

    private static final Logger log = LoggerFactory.getLogger(ServicePaiementMobile.class);

    /**
     * Au-delà de ce délai, un paiement resté en attente est déclaré échoué.
     *
     * <p>Volontairement <b>plus court</b> que le délai de libération du stock
     * de {@code ServiceCommande} : le paiement doit être tranché avant que la
     * commande ne soit annulée, sinon on encaisserait un client dont la
     * commande vient d'être libérée.</p>
     */
    private static final Duration DELAI_ABANDON = Duration.ofMinutes(20);

    private final ServicePaiement paiementsMetier;
    private final PaiementRepository paiements;
    private final CommandeRepository commandes;
    private final ClientCampay campay;
    private final com.garah.api.commun.audit.JournalParcours parcours;

    public ServicePaiementMobile(ServicePaiement paiementsMetier,
                                 PaiementRepository paiements,
                                 CommandeRepository commandes,
                                 ClientCampay campay,
                                 com.garah.api.commun.audit.JournalParcours parcours) {
        this.parcours = parcours;
        this.paiementsMetier = paiementsMetier;
        this.paiements = paiements;
        this.commandes = commandes;
        this.campay = campay;
    }

    /** Ce qu'on renvoie au client pour qu'il termine sur son téléphone. */
    public record DemandePaiement(Long paiementId, String reference, String codeUssd,
                                  String operateur, String statut) {
    }

    /**
     * Démarre un paiement mobile money sur une commande.
     *
     * <p>⚠️ <b>Le montant n'est jamais fourni par l'appelant</b> :
     * {@link ServicePaiement#initier} le lit sur la commande. C'est la faille
     * la plus classique d'un tunnel de paiement — payer 100 FCFA une commande
     * de 100 000.</p>
     *
     * <p><b>Sur la transaction.</b> L'appel HTTP à Campay est fait <b>hors</b>
     * de toute transaction longue : garder une connexion PostgreSQL ouverte
     * pendant un appel réseau qui peut durer trente secondes épuiserait le pool
     * Hikari, réduit à 5 connexions sur Neon (D-14). Les deux écritures sont
     * donc deux transactions courtes, et l'état intermédiaire ({@code INITIE}
     * sans référence) est rattrapé par {@link #reconcilier()}.</p>
     *
     * @param clientId le client authentifié — vérifié contre la commande
     */
    public DemandePaiement demander(Long commandeId, Long clientId,
                                    MoyenPaiement moyen, String telephone) {

        if (moyen == MoyenPaiement.VIREMENT) {
            throw new RegleMetierViolee("MOYEN_NON_MOBILE",
                    "Le virement bancaire ne se règle pas en ligne. "
                    + "Contactez le service client pour obtenir les coordonnées.");
        }

        verifierProprietaire(commandeId, clientId);

        // Transaction courte n°1 : notre ligne de paiement.
        Paiement paiement = paiementsMetier.initier(commandeId, moyen);

        // Appel réseau, HORS transaction.
        ClientCampay.Collecte collecte;
        try {
            collecte = campay.encaisser(
                    paiement.getMontant(),
                    normaliserTelephone(telephone),
                    "GARAH " + numeroDe(commandeId),
                    String.valueOf(paiement.getId()));
        } catch (RuntimeException e) {
            // L'opérateur n'a pas pris la demande. On clôt notre ligne pour ne
            // pas laisser un paiement INITIE fantôme que la réconciliation
            // interrogerait indéfiniment sans référence.
            paiementsMetier.echouer(paiement.getId(), "OPERATEUR_INJOIGNABLE",
                    e.getClass().getSimpleName(), clientId);
            throw e;
        }

        // Transaction courte n°2 : on note la référence de l'opérateur.
        paiementsMetier.enregistrerAupresOperateur(paiement.getId(), collecte.reference());

        // ⚠️ On trace la DEMANDE, pas l'encaissement. Le paiement mobile est
        //    asynchrone : à cet instant le client n'a encore rien payé, il a
        //    reçu un code à composer. Écrire « PAIEMENT » comme s'il était
        //    abouti ferait mentir le parcours sur ce qui compte le plus.
        parcours.paiement(commandeId, moyen.name(), null);

        return new DemandePaiement(paiement.getId(), collecte.reference(),
                collecte.codeUssd(), collecte.operateur(), StatutPaiement.EN_ATTENTE.name());
    }

    /**
     * Traite une notification de l'opérateur — <b>sans croire ce qu'elle dit</b>.
     *
     * <p>Idempotente par construction : elle délègue à
     * {@link ServicePaiement#confirmer}, qui sort sans erreur si le paiement
     * est déjà confirmé. MTN et Orange rejouent leurs notifications tant
     * qu'ils n'ont pas d'accusé de réception — deux appels pour le même
     * paiement sont la norme, pas l'exception.</p>
     *
     * @param reference la référence Campay, seule donnée retenue de la notification
     * @return vrai si l'état a été tranché (confirmé ou échoué)
     */
    public boolean traiterNotification(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }

        Optional<Paiement> trouve = paiements.findByReferenceTransaction(reference);
        if (trouve.isEmpty()) {
            // Référence inconnue : très probablement quelqu'un qui tâtonne sur
            // notre webhook. On le note et on s'arrête — surtout sans appeler
            // Campay, sinon n'importe qui pourrait nous faire émettre des
            // requêtes sortantes à volonté.
            log.warn("Notification de paiement pour une reference inconnue : {}", reference);
            return false;
        }

        return trancher(trouve.get());
    }

    /**
     * Demande son état réel à l'opérateur, puis en tire les conséquences.
     *
     * @return vrai si le paiement a changé d'état
     */
    private boolean trancher(Paiement paiement) {
        if (paiement.estConfirme()
                || paiement.getStatut() == StatutPaiement.ECHOUE
                || paiement.getStatut() == StatutPaiement.ANNULE) {
            return false;   // déjà clos : rien à faire, et surtout pas d'erreur
        }

        Optional<ClientCampay.EtatTransaction> etat =
                campay.statut(paiement.getReferenceTransaction());

        if (etat.isEmpty()) {
            log.warn("Campay ne connait pas la reference {} du paiement {}",
                    paiement.getReferenceTransaction(), paiement.getId());
            return false;
        }

        ClientCampay.EtatTransaction transaction = etat.get();

        if (transaction.enCours()) {
            return false;   // le client n'a pas encore validé sur son téléphone
        }

        if (transaction.echoue()) {
            paiementsMetier.echouer(paiement.getId(),
                    transaction.codeErreur() == null ? "ECHEC_OPERATEUR" : transaction.codeErreur(),
                    "Refusé par l'opérateur", clientDe(paiement));
            return true;
        }

        // ⚠️ Succès annoncé — on vérifie quand même le MONTANT.
        //
        // Sans ce contrôle, une transaction réussie de 100 FCFA confirmerait
        // une commande de 100 000. Le cas n'a rien de théorique : il suffit
        // qu'un client relance un ancien paiement d'un autre montant, ou qu'une
        // référence soit rapprochée de travers.
        if (transaction.montant() == null
                || transaction.montant().compareTo(paiement.getMontant()) < 0) {

            log.error("Montant incoherent sur le paiement {} : attendu {}, encaisse {}",
                    paiement.getId(), paiement.getMontant(), transaction.montant());

            paiementsMetier.echouer(paiement.getId(), "MONTANT_INCOHERENT",
                    "Le montant encaissé ne correspond pas à la commande.",
                    clientDe(paiement));
            return true;
        }

        // La référence enregistrée reste celle de Campay : c'est elle qui rend
        // un litige arbitrable, et l'index unique paiement_reference_unique
        // (V18) empêche de l'enregistrer deux fois.
        paiementsMetier.confirmer(paiement.getId(), paiement.getReferenceTransaction());
        return true;
    }

    /**
     * Rattrape les paiements dont la notification ne nous est jamais parvenue.
     *
     * <p><b>Un webhook se perd.</b> Réseau coupé, instance Render endormie,
     * déploiement en cours : la notification part et personne ne la reçoit.
     * Sans ce filet, le client est débité et sa commande reste
     * {@code EN_ATTENTE_PAIEMENT} jusqu'à ce que le travail de libération
     * l'annule — le pire résultat possible, et celui dont le support ne se
     * relève pas.</p>
     *
     * <p>Ne traite que les paiements portant déjà une référence : les autres
     * n'ont jamais atteint l'opérateur.</p>
     *
     * @return le nombre de paiements tranchés
     */
    @Transactional(readOnly = true)
    public List<Long> enAttenteAReconcilier() {
        return paiements.enAttenteAvecReference(StatutPaiement.EN_ATTENTE).stream()
                .map(Paiement::getId)
                .toList();
    }

    /**
     * Le travail périodique de réconciliation.
     *
     * <p>Chaque paiement est tranché dans <b>sa propre transaction</b> : un
     * paiement dont l'opérateur est injoignable ne doit pas annuler le travail
     * fait sur les précédents.</p>
     *
     * @return le nombre de paiements dont l'état a changé
     */
    public int reconcilier() {
        if (!campay.estConfigure()) {
            return 0;
        }

        int tranches = 0;
        for (Long id : enAttenteAReconcilier()) {
            try {
                if (reconcilierUn(id)) {
                    tranches++;
                }
            } catch (RuntimeException e) {
                // On continue : les autres paiements n'y sont pour rien.
                log.warn("Reconciliation impossible pour le paiement {} : {}",
                        id, e.getClass().getSimpleName());
            }
        }
        return tranches;
    }

    /**
     * Un paiement, une transaction.
     *
     * <p>Abandonne les paiements trop vieux : au-delà de {@link #DELAI_ABANDON},
     * le client a fermé son téléphone depuis longtemps. Les laisser en attente
     * ferait interroger Campay indéfiniment pour des transactions mortes.</p>
     */
    @Transactional
    public boolean reconcilierUn(Long paiementId) {
        Paiement paiement = paiements.findById(paiementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Paiement", paiementId));

        boolean tranche = trancher(paiement);

        if (!tranche && paiement.getDateInitiation().isBefore(Instant.now().minus(DELAI_ABANDON))) {
            paiementsMetier.echouer(paiementId, "DELAI_DEPASSE",
                    "Le paiement n'a pas été validé dans le délai imparti.",
                    clientDe(paiement));
            return true;
        }

        return tranche;
    }

    // -------------------------------------------------------------------------

    private void verifierProprietaire(Long commandeId, Long clientId) {
        Commande commande = commandes.findById(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        // ⚠️ On répond « introuvable », pas « interdit ».
        //
        // Un 403 confirmerait à l'appelant que la commande 41 existe. En
        // parcourant les identifiants, il reconstituerait le volume d'affaires
        // de la plateforme sans jamais voir une seule commande.
        if (!commande.getClientId().equals(clientId)) {
            throw RessourceIntrouvable.de("Commande", commandeId);
        }
    }

    private String numeroDe(Long commandeId) {
        return commandes.findById(commandeId)
                .map(Commande::getNumero)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));
    }

    private Long clientDe(Paiement paiement) {
        return commandes.findById(paiement.getCommandeId())
                .map(Commande::getClientId)
                .orElse(null);
    }

    /**
     * Le format attendu par l'opérateur : indicatif pays, sans {@code +}.
     *
     * <p>Les vraies personnes écrivent {@code +237 6 99 00 00 00},
     * {@code 699000000} ou {@code 00237699000000}. Refuser tout sauf une forme
     * canonique ferait échouer des paiements pour un espace ; on normalise.</p>
     *
     * <p>{@code public static} pour être testable directement : c'est du calcul
     * pur, et le vérifier ne doit demander ni base ni réseau.</p>
     */
    public static String normaliserTelephone(String telephone) {
        if (telephone == null) {
            throw new RegleMetierViolee("TELEPHONE_MANQUANT",
                    "Le numéro de téléphone mobile money est obligatoire.");
        }

        String chiffres = telephone.replaceAll("[^0-9]", "");

        // 00237… → 237…
        if (chiffres.startsWith("00")) {
            chiffres = chiffres.substring(2);
        }
        // 6xxxxxxxx (9 chiffres, format camerounais local) → 237 6xxxxxxxx
        if (chiffres.length() == 9) {
            chiffres = "237" + chiffres;
        }

        if (chiffres.length() < 11 || chiffres.length() > 15) {
            throw new RegleMetierViolee("TELEPHONE_INVALIDE",
                    "Ce numéro de téléphone n'est pas exploitable pour un paiement mobile.");
        }

        return chiffres;
    }

    /** Le paiement, tel qu'on le montre au client qui interroge son état. */
    @Transactional(readOnly = true)
    public DemandePaiement etat(Long paiementId, Long clientId) {
        Paiement paiement = paiements.findById(paiementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Paiement", paiementId));

        verifierProprietaire(paiement.getCommandeId(), clientId);

        return new DemandePaiement(paiement.getId(), paiement.getReferenceTransaction(),
                null, paiement.getMoyen().name(), paiement.getStatut().name());
    }

    /**
     * Force la vérification d'un paiement à la demande du client.
     *
     * <p>Utile quand l'écran de suivi tourne depuis trente secondes : plutôt
     * que d'attendre la réconciliation, on redemande. Réservé au propriétaire
     * de la commande, et sans effet si le paiement est déjà clos.</p>
     */
    public DemandePaiement verifier(Long paiementId, Long clientId) {
        Paiement paiement = paiements.findById(paiementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Paiement", paiementId));

        verifierProprietaire(paiement.getCommandeId(), clientId);

        if (paiement.getReferenceTransaction() == null) {
            throw new ConflitEtat("PAIEMENT_SANS_REFERENCE",
                    "Ce paiement n'a jamais atteint l'opérateur.");
        }

        reconcilierUn(paiementId);
        return etat(paiementId, clientId);
    }
}
