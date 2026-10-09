package com.garah.api.commerce.domaine;

import com.garah.api.commerce.infra.PasserellePaiement;
import com.garah.api.commerce.infra.CommandeRepository;
import com.garah.api.commerce.infra.PaiementRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.domaine.ServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Le paiement mobile money de bout en bout : notre domaine et l'opérateur.
 *
 * <p>{@link ServicePaiement} ne connaît que nos propres règles et ne parle à
 * personne. Cette classe-ci est la seule à faire dialoguer les deux mondes.
 * La séparation n'est pas cosmétique : les 10 tests de {@code ServicePaiement}
 * tournent sans réseau, et ont continué de tourner quand MoneyFusion est venu
 * s'ajouter à Campay (D-55).</p>
 *
 * <h2>Le cycle complet</h2>
 * <pre>
 * 1. demander()        notre Paiement INITIE → fournisseur actif → EN_ATTENTE
 * 2. Campay      : le client saisit son code sur son téléphone
 *    MoneyFusion : le client paie sur la page dont on lui donne l'adresse
 * 3a. le fournisseur appelle notre webhook   → traiterNotification()
 * 3b. ou le webhook se perd                  → reconcilier() le rattrape
 * 4. ServicePaiement.confirmer()  → commande PAYEE, stock sorti, grand livre
 * </pre>
 *
 * <p>⚠️ <b>Deux fournisseurs, une règle</b> : un paiement est toujours
 * interrogé chez celui qui l'a reçu (colonne {@code fournisseur}, V40), jamais
 * chez le fournisseur actif du jour. Basculer de Campay à MoneyFusion ne doit
 * pas rendre orphelins les paiements Campay encore en attente.</p>
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
    private final ServiceClient clients;
    private final com.garah.api.commun.audit.JournalParcours parcours;

    /** Toutes les passerelles connues, par fournisseur. */
    private final Map<FournisseurPaiement, PasserellePaiement> passerelles;

    /**
     * Celui qui reçoit les NOUVEAUX paiements ({@code GARAH_PAIEMENT_FOURNISSEUR}).
     *
     * <p>⚠️ Ne sert qu'à créer. Pour interroger un paiement existant, on lit
     * son propre fournisseur : voir {@link #passerelleDe(Paiement)}.</p>
     */
    private final FournisseurPaiement actif;

    /**
     * Où MoneyFusion renvoie le client après paiement, avec deux gabarits :
     * {@code {commande}} et {@code {paiement}}. Vide : pas de retour, le client
     * reste sur la page MoneyFusion.
     */
    private final String gabaritRetour;

    public ServicePaiementMobile(ServicePaiement paiementsMetier,
                                 PaiementRepository paiements,
                                 CommandeRepository commandes,
                                 ServiceClient clients,
                                 List<PasserellePaiement> passerelles,
                                 com.garah.api.commun.audit.JournalParcours parcours,
                                 @Value("${GARAH_PAIEMENT_FOURNISSEUR:CAMPAY}") String actif,
                                 @Value("${GARAH_MONEYFUSION_URL_RETOUR:}") String gabaritRetour) {
        this.parcours = parcours;
        this.paiementsMetier = paiementsMetier;
        this.paiements = paiements;
        this.commandes = commandes;
        this.clients = clients;
        this.passerelles = new EnumMap<>(FournisseurPaiement.class);
        passerelles.forEach(p -> this.passerelles.put(p.fournisseur(), p));
        this.actif = fournisseurActif(actif);
        this.gabaritRetour = gabaritRetour == null ? "" : gabaritRetour.strip();

        if (!this.passerelles.containsKey(this.actif)) {
            throw new IllegalStateException("Aucune passerelle pour le fournisseur " + this.actif);
        }
        log.info("Paiement mobile : nouveaux paiements chez {}", this.actif);
    }

    /**
     * Lit le réglage, et <b>refuse de démarrer</b> sur une valeur inconnue.
     *
     * <p>⚠️ Retomber sur Campay en silence serait le pire choix : une faute de
     * frappe (« MONEY_FUSION ») ferait encaisser chez un fournisseur que
     * personne n'a choisi, en bac à sable, sans que rien ne le signale. Un
     * serveur qui ne démarre pas se voit au déploiement.</p>
     */
    static FournisseurPaiement fournisseurActif(String valeur) {
        String v = valeur == null ? "" : valeur.strip().toUpperCase(java.util.Locale.ROOT);
        if (v.isEmpty()) {
            return FournisseurPaiement.CAMPAY;
        }
        try {
            return FournisseurPaiement.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("GARAH_PAIEMENT_FOURNISSEUR=" + valeur
                    + " est inconnu. Valeurs acceptees : CAMPAY, MONEYFUSION.");
        }
    }

    /**
     * La passerelle qui a reçu CE paiement.
     *
     * <p>Un paiement sans fournisseur ne peut être qu'antérieur à V40, qui les
     * a tous rattachés à Campay ; on retombe donc sur Campay.</p>
     */
    private PasserellePaiement passerelleDe(Paiement paiement) {
        FournisseurPaiement f = paiement.getFournisseur() == null
                ? FournisseurPaiement.CAMPAY : paiement.getFournisseur();
        return passerelles.get(f);
    }

    /**
     * L'état d'un paiement, tel que les écrans le lisent.
     *
     * <h2>🎯 Ce record ne portait pas les bons noms</h2>
     *
     * <p>Il annonçait {@code paiementId} et {@code reference} là où les deux
     * applications lisent {@code id} et {@code referenceTransaction}. Le
     * paiement partait donc correctement — l'opérateur poussait bien sa
     * demande de code sur le téléphone — et l'écran <b>échouait au retour</b>,
     * en lisant une réponse dont il ne reconnaissait aucun champ.</p>
     *
     * <p>⚠️ Le symptôme trompe : « une erreur inattendue » APRÈS que le
     * téléphone a sonné. On cherche du côté de l'opérateur, qui a
     * parfaitement fait son travail.</p>
     *
     * <p>⚠️ Et {@code operateur} portait <b>deux sens</b> selon la route :
     * l'opérateur annoncé par Campay à la demande, notre propre moyen de
     * paiement à la relecture. Un même champ, deux significations — donc un
     * champ sur lequel on ne peut rien construire.</p>
     *
     * @param montant     ce que le client paie. Il manquait, et l'écran ne
     *                    pouvait donc pas le rappeler au moment de valider.
     * @param moyen       le nôtre : {@code MTN_MOMO} ou {@code ORANGE_MONEY}.
     *                    {@code null} tant que le client ne l'a pas choisi sur
     *                    la page MoneyFusion.
     * @param codeUssd    Campay : à composer si la demande n'arrive pas
     *                    d'elle-même. {@code null} en dehors de la demande
     *                    initiale.
     * @param urlPaiement MoneyFusion : la page où le client paie. {@code null}
     *                    en dehors de la demande initiale, et avec Campay.
     * @param urlRetour   MoneyFusion : l'adresse où la page renvoie le client
     *                    une fois le paiement fait. L'application mobile la
     *                    guette pour refermer la page d'elle-même.
     */
    public record DemandePaiement(Long id, String statut, java.math.BigDecimal montant,
                                  String moyen, String referenceTransaction,
                                  String codeUssd, String urlPaiement, String urlRetour) {
    }

    /**
     * Démarre un paiement mobile money sur une commande.
     *
     * <p>⚠️ <b>Le montant n'est jamais fourni par l'appelant</b> :
     * {@link ServicePaiement#initier} le lit sur la commande. C'est la faille
     * la plus classique d'un tunnel de paiement — payer 100 FCFA une commande
     * de 100 000.</p>
     *
     * <p><b>Sur la transaction.</b> L'appel HTTP au fournisseur est fait <b>hors</b>
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

        PasserellePaiement passerelle = passerelles.get(actif);

        // Campay fait sonner UN téléphone, chez UN opérateur : il faut le
        // savoir avant. MoneyFusion le demande sur sa page.
        //
        // ⚠️ Avec MoneyFusion, un moyen envoyé par l'écran est IGNORÉ. Le
        //    client peut choisir Orange chez nous puis payer en MTN sur la
        //    page : retenir le premier inscrirait un fait faux. Le vrai moyen
        //    arrive avec la confirmation.
        if (actif == FournisseurPaiement.CAMPAY && moyen == null) {
            throw new RegleMetierViolee("MOYEN_OBLIGATOIRE",
                    "Choisissez MTN MoMo ou Orange Money.");
        }
        MoyenPaiement moyenRetenu = actif == FournisseurPaiement.CAMPAY ? moyen : null;

        String numero = normaliserTelephone(telephone);
        verifierProprietaire(commandeId, clientId);

        // Transaction courte n°1 : notre ligne de paiement.
        Paiement paiement = paiementsMetier.initier(commandeId, moyenRetenu, actif);
        String urlRetour = urlRetour(commandeId, paiement.getId());

        // Appel réseau, HORS transaction.
        PasserellePaiement.Collecte collecte;
        try {
            collecte = passerelle.encaisser(new PasserellePaiement.DemandeEncaissement(
                    paiement.getId(), commandeId, numeroDe(commandeId),
                    paiement.getMontant(), numero, nomDuClient(clientId), urlRetour));
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
        parcours.paiement(commandeId, moyenRetenu == null ? actif.name() : moyenRetenu.name(), null);

        return new DemandePaiement(paiement.getId(), StatutPaiement.EN_ATTENTE.name(),
                paiement.getMontant(), moyenRetenu == null ? null : moyenRetenu.name(),
                collecte.reference(), collecte.codeUssd(), collecte.urlPaiement(),
                collecte.urlPaiement() == null ? null : urlRetour);
    }

    /**
     * L'adresse de retour de la page de paiement, pour CE paiement.
     *
     * <p>Elle porte l'identifiant du paiement : la boutique web est rechargée
     * de zéro au retour, et n'a plus rien en mémoire. Sans lui, elle ne saurait
     * pas quel paiement vérifier.</p>
     *
     * <p>⚠️ Fabriquée par le SERVEUR, jamais reçue de l'écran. Une adresse de
     * retour fournie par l'appelant ferait de GARAH un tremplin vers n'importe
     * quel site, avec notre nom dans la barre d'adresse juste avant.</p>
     */
    private String urlRetour(Long commandeId, Long paiementId) {
        if (gabaritRetour.isEmpty()) {
            return null;
        }
        return gabaritRetour
                .replace("{commande}", String.valueOf(commandeId))
                .replace("{paiement}", String.valueOf(paiementId));
    }

    /** MoneyFusion l'affiche dans son tableau de bord ; Campay l'ignore. */
    private String nomDuClient(Long clientId) {
        var nom = clients.nomsPar(List.of(clientId)).get(clientId);
        return nom == null ? null : nom.nom();
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
     * @param reference la référence chez le fournisseur, seule donnée retenue de la notification
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
            // le fournisseur, sinon n'importe qui pourrait nous faire émettre des
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

        PasserellePaiement passerelle = passerelleDe(paiement);
        if (!passerelle.estConfigure()) {
            // Le fournisseur de CE paiement n'a plus ses variables : on ne
            // peut rien lui demander. Surtout pas l'abandonner — l'argent est
            // peut-être chez lui.
            log.warn("Paiement {} : {} n'est plus configure, etat non verifiable",
                    paiement.getId(), passerelle.fournisseur());
            return false;
        }

        Optional<PasserellePaiement.EtatTransaction> etat =
                passerelle.statut(paiement.getReferenceTransaction());

        if (etat.isEmpty()) {
            log.warn("{} ne connait pas la reference {} du paiement {}",
                    passerelle.fournisseur(), paiement.getReferenceTransaction(), paiement.getId());
            return false;
        }

        PasserellePaiement.EtatTransaction transaction = etat.get();

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

        // La référence enregistrée reste celle du fournisseur : c'est elle qui
        // rend un litige arbitrable, et l'index unique paiement_reference_unique
        // (V18) empêche de l'enregistrer deux fois.
        //
        // ⚠️ Montant brut ET frais journalisés : c'est cette ligne qui dira,
        //    au premier paiement réel MoneyFusion, si « Montant » est le brut
        //    ou le net (voir ClientMoneyFusion.montantPaye).
        log.info("Paiement {} confirme par {} : attendu {}, encaisse {}, moyen annonce {}",
                paiement.getId(), passerelle.fournisseur(), paiement.getMontant(),
                transaction.montant(), transaction.operateur());

        paiementsMetier.confirmer(paiement.getId(), paiement.getReferenceTransaction(),
                moyenDe(transaction.operateur()), transaction.operateur());
        return true;
    }

    /**
     * Traduit le moyen annoncé par un fournisseur dans le nôtre.
     *
     * <p>MoneyFusion écrit « orange » dans un exemple, « orange-cm » dans sa
     * liste de moyens ; Campay « MTN » ou « Orange ». On cherche le nom de
     * l'opérateur dans le libellé plutôt qu'une valeur exacte.</p>
     *
     * @return {@code null} si on ne sait pas le nommer — le paiement est
     * confirmé quand même : l'argent est encaissé, et l'annonce brute reste
     * en base pour qu'on la lise
     */
    static MoyenPaiement moyenDe(String annonce) {
        if (annonce == null) {
            return null;
        }
        String bas = annonce.toLowerCase(java.util.Locale.ROOT);
        if (bas.contains("mtn") || bas.contains("momo")) {
            return MoyenPaiement.MTN_MOMO;
        }
        if (bas.contains("orange")) {
            return MoyenPaiement.ORANGE_MONEY;
        }
        return null;
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
        // ⚠️ Plus de sortie anticipée « Campay n'est pas configuré » : il y a
        //    maintenant deux fournisseurs, et chaque paiement est interrogé
        //    chez le sien. trancher() saute ceux dont le fournisseur n'est pas
        //    configuré, un par un.
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
     * ferait interroger le fournisseur indéfiniment pour des transactions mortes.</p>
     */
    @Transactional
    public boolean reconcilierUn(Long paiementId) {
        Paiement paiement = paiements.findById(paiementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Paiement", paiementId));

        boolean tranche = trancher(paiement);

        // ⚠️ On n'abandonne pas un paiement dont le fournisseur n'est pas
        //    configuré : on n'a pas pu lui demander, donc on ne sait pas.
        //    C'était déjà le cas avant (reconcilier() sortait tout de suite).
        if (!tranche && passerelleDe(paiement).estConfigure()
                && paiement.getDateInitiation().isBefore(Instant.now().minus(DELAI_ABANDON))) {
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

        // ⚠️ Pas de code USSD ici : il n'est valable qu'au moment de la
        //    demande. Le rendre à la relecture ferait composer un code périmé.
        //    Pas de page de paiement non plus, pour la même raison : une
        //    session MoneyFusion se rouvre en relançant le paiement.
        return new DemandePaiement(paiement.getId(), paiement.getStatut().name(),
                paiement.getMontant(),
                paiement.getMoyen() == null ? null : paiement.getMoyen().name(),
                paiement.getReferenceTransaction(), null, null, null);
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
