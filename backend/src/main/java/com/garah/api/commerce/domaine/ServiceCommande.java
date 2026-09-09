package com.garah.api.commerce.domaine;

import com.garah.api.catalogue.domaine.InfoVenteVariante;
import com.garah.api.catalogue.domaine.ServiceTarification;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commerce.infra.CommandeRepository;
import com.garah.api.commerce.infra.PanierRepository;
import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.audit.JournalParcours;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.domaine.NomClient;
import com.garah.api.iam.domaine.ServiceClient;
import com.garah.api.iam.domaine.ServiceVerificationEmail;
import com.garah.api.logistique.domaine.Lieu;
import com.garah.api.logistique.domaine.TypeLieu;
import com.garah.api.logistique.infra.LieuRepository;
import com.garah.api.marchand.domaine.ServiceCommission;
import com.garah.api.stock.domaine.ServiceStock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Le passage de commande : l'opération la plus lourde de tout le backend.
 *
 * <p>Elle touche <b>cinq domaines</b> en une seule transaction : commerce,
 * catalogue (désignation), tarification (prix), marchand (commission),
 * logistique (frais) et stock (réservation).</p>
 *
 * <p>Et elle doit être <b>atomique</b>. Le chapitre 04 le disait :</p>
 * <blockquote>Ce qui doit être vrai ensemble s'écrit ensemble.</blockquote>
 *
 * <p>Une réservation de stock validée sans la commande correspondante
 * immobiliserait de la marchandise pour un client qui n'existe pas — et
 * personne ne s'en apercevrait avant l'inventaire.</p>
 */
@Service
public class ServiceCommande {

    /** Au-delà, une commande impayée est annulée et son stock libéré. */
    private static final Duration DELAI_PAIEMENT = Duration.ofMinutes(30);

    /** Transitions autorisées (chapitre 04 §4.1). */
    private static final Map<StatutCommande, Set<StatutCommande>> TRANSITIONS = Map.of(
            StatutCommande.EN_ATTENTE_PAIEMENT, Set.of(StatutCommande.PAYEE, StatutCommande.ANNULEE),
            StatutCommande.PAYEE,               Set.of(StatutCommande.EN_PREPARATION, StatutCommande.ANNULEE),
            StatutCommande.EN_PREPARATION,      Set.of(StatutCommande.PRETE),
            StatutCommande.PRETE,               Set.of(StatutCommande.EXPEDIEE),
            StatutCommande.EXPEDIEE,            Set.of(StatutCommande.DISPONIBLE),
            StatutCommande.DISPONIBLE,          Set.of(StatutCommande.RETIREE),
            StatutCommande.RETIREE,             Set.of(),
            StatutCommande.ANNULEE,             Set.of());

    private final CommandeRepository commandes;
    private final PanierRepository paniers;
    private final VarianteRepository variantes;
    private final ServiceTarification tarification;
    private final ServiceCommission commissions;
    private final ServiceStock stock;
    private final LieuRepository lieux;
    private final ServiceVerificationEmail verification;

    /** La lecture des clients, pour afficher un nom plutot qu un identifiant. */
    private final ServiceClient clients;

    /**
     * ⚠️ Le journal ne retient QUE ce qui vient de la maison.
     *
     * <p>Un client qui annule sa propre commande n'écrit rien ici — l'écouteur
     * écarte les acteurs externes. Sans quoi le journal des actions internes
     * serait rempli d'annulations de clients, et la seule qui compte — celle
     * qu'un employé a décidée — deviendrait introuvable.</p>
     */
    private final JournalActions journal;

    /**
     * ⚠️ Le journal du PARCOURS, qui n'est pas celui des actions internes.
     *
     * <p>Passer commande est le geste d'un client : il n'a rien à faire dans
     * le journal d'audit, qui répond à « qui, chez nous, a touché à cette
     * donnée ». Voir {@code activite_client}, declarée en V12 et restee vide
     * trois versions durant.</p>
     */
    private final JournalParcours parcours;

    public ServiceCommande(CommandeRepository commandes, PanierRepository paniers,
                           VarianteRepository variantes, ServiceTarification tarification,
                           ServiceCommission commissions, ServiceStock stock,
                           LieuRepository lieux, ServiceVerificationEmail verification,
                           ServiceClient clients, JournalActions journal,
                           JournalParcours parcours) {
        this.journal = journal;
        this.parcours = parcours;
        this.commandes = commandes;
        this.paniers = paniers;
        this.variantes = variantes;
        this.tarification = tarification;
        this.commissions = commissions;
        this.stock = stock;
        this.lieux = lieux;
        this.verification = verification;
        this.clients = clients;
    }

    /**
     * Transforme le panier actif en commande.
     *
     * <p>L'ordre des opérations n'est pas indifférent :</p>
     * <ol>
     *   <li>valider le point de récupération ;</li>
     *   <li>construire les lignes en <b>figeant</b> désignation, prix,
     *       marchand, TVA et commission ;</li>
     *   <li><b>réserver le stock</b> — c'est là que ça peut échouer ;</li>
     *   <li>calculer les totaux et convertir le panier.</li>
     * </ol>
     *
     * <p>La réservation vient <b>après</b> la construction des lignes mais
     * <b>dans</b> la même transaction : si elle échoue, tout est annulé, y
     * compris la commande. Le client reçoit un 409 et son panier est intact.</p>
     */
    @Transactional
    public DetailCommande passer(Long clientId, Long pointRecuperationId, String langue) {
        exigerAdresseConfirmee(clientId);

        Panier panier = paniers.chargerActifAvecLignes(clientId)
                .orElseThrow(() -> new RegleMetierViolee("PANIER_VIDE",
                        "Votre panier est vide."));

        if (panier.estVide()) {
            throw new RegleMetierViolee("PANIER_VIDE", "Votre panier est vide.");
        }

        Lieu pointRetrait = validerPointRecuperation(pointRecuperationId);

        // ⚠️ La commande est enregistrée avec TOUS ses montants à zéro.
        //
        // La contrainte commande_total_coherent impose
        //     montant_total = montant_articles + montant_frais − montant_remise
        // et elle est vérifiée à CHAQUE écriture, pas seulement à la fin.
        //
        // Poser les frais dès maintenant, alors qu'aucune ligne n'existe,
        // donnerait 0 ≠ 0 + 8 000 − 0 et l'insertion échouerait. La contrainte
        // impose donc une discipline : la ligne doit être COHÉRENTE à tout
        // instant, jamais seulement à la fin du traitement.
        Commande commande = new Commande(genererNumero(), clientId, pointRecuperationId,
                langue == null ? "fr" : langue);
        commandes.save(commande);

        for (LignePanier ligne : panier.getLignes()) {
            commande.ajouterLigne(construireLigne(commande, ligne));
        }

        reserverLeStock(commande);

        // Frais et totaux ensemble : le prochain flush verra un état cohérent.
        commande.setMontantFrais(pointRetrait.getFraisAcheminement());
        commande.recalculer();
        panier.convertir();

        // ⚠️ Après `convertir()` : le geste n'est vrai qu'une fois le panier
        //    devenu commande. Le tracer plus tôt journaliserait des commandes
        //    que la réservation de stock peut encore faire échouer.
        parcours.commande(commande.getId(), commande.getNumero(),
                commande.getMontantTotal());

        return DetailCommande.de(commande);
    }

    /**
     * Construit une ligne en figeant tout ce qui a une valeur juridique.
     *
     * <p>C'est le cœur de la règle de la photographie. Cinq valeurs sont
     * copiées ici et ne bougeront plus jamais.</p>
     */
    private LigneCommande construireLigne(Commande commande, LignePanier ligne) {
        InfoVenteVariante info = variantes.infoVente(ligne.getVarianteId())
                .orElseThrow(() -> RessourceIntrouvable.de("Variante", ligne.getVarianteId()));

        if (!info.estVendable()) {
            throw new ConflitEtat("ARTICLE_INDISPONIBLE",
                    "« " + info.designation() + " » n'est plus proposé à la vente.");
        }

        BigDecimal prix = tarification.prixUnitaire(ligne.getVarianteId(), ligne.getQuantite());
        BigDecimal tauxCommission = commissions.tauxPour(info.marchandId(), info.categorieProduitId());

        return new LigneCommande(commande, ligne.getVarianteId(), info.marchandId(),
                info.designation(), null, ligne.getQuantite(),
                prix, info.tauxTva(), tauxCommission);
    }

    /**
     * Réserve le stock de toutes les lignes.
     *
     * <p>⚠️ <b>L'ordre est trié par identifiant de variante</b>, et ce n'est
     * pas cosmétique : deux commandes contenant les mêmes articles dans un
     * ordre différent pourraient s'attendre mutuellement et provoquer un
     * interblocage (chapitre 11 §5).</p>
     */
    private void reserverLeStock(Commande commande) {
        commande.getLignes().stream()
                .sorted(Comparator.comparing(LigneCommande::getVarianteId))
                .forEach(l -> stock.reserver(l.getVarianteId(), l.getQuantite(), commande.getId()));
    }

    private Lieu validerPointRecuperation(Long lieuId) {
        Lieu lieu = lieux.findById(lieuId)
                .orElseThrow(() -> RessourceIntrouvable.de("Point de récupération", lieuId));

        // La clé étrangère composite refuserait de toute façon un lieu du
        // mauvais type. On le vérifie ici pour produire un message utile
        // plutôt qu'une erreur d'intégrité (chapitre 04 §3).
        if (lieu.getType() != TypeLieu.POINT_RECUPERATION) {
            throw new RegleMetierViolee("LIEU_INVALIDE",
                    "Ce lieu n'est pas un point de récupération.");
        }
        if (!lieu.estActif()) {
            throw new RegleMetierViolee("POINT_RECUPERATION_INACTIF",
                    "Ce point de récupération n'accepte plus de commandes.");
        }
        return lieu;
    }

    /**
     * Refuse la commande tant que l'adresse e-mail n'est pas confirmée (D-23).
     *
     * <p>🎯 <b>C'est ici que la barrière tombe, et pas à la connexion.</b></p>
     *
     * <p>Bloquer la connexion serait plus strict et plus mauvais : le client ne
     * pourrait même pas demander un nouveau lien, et le premier e-mail perdu
     * fermerait le compte définitivement. On le laisse donc parcourir le
     * catalogue et remplir son panier — puis on barre au dernier moment utile,
     * celui où l'adresse commence réellement à servir.</p>
     *
     * <p>Car à partir d'ici, tout en dépend : le numéro de commande, le code de
     * retrait, les avis d'acheminement (D-07). Une adresse fausse, et la
     * marchandise arrive à Bangui sans que personne ne puisse être prévenu — un
     * défaut invisible à l'inscription, qui se découvre devant le point de
     * récupération.</p>
     */
    private void exigerAdresseConfirmee(Long clientId) {
        if (!verification.estConfirme(clientId)) {
            throw new ServiceVerificationEmail.AdresseNonConfirmee();
        }
    }

    /** {@code CMD-2026-000042}, tiré d'une séquence PostgreSQL (V17). */
    private String genererNumero() {
        return "CMD-%d-%06d".formatted(Year.now().getValue(), commandes.prochainNumero());
    }

    // -------------------------------------------------------------------------
    // Cycle de vie
    // -------------------------------------------------------------------------

    /**
     * Annule une commande et <b>libère le stock réservé</b>.
     *
     * <p>Les deux vont ensemble, toujours. Annuler sans libérer laisserait de
     * la marchandise immobilisée sans propriétaire.</p>
     */
    @Transactional
    public DetailCommande annuler(Long commandeId, String motif) {
        Commande commande = charger(commandeId);
        verifierTransition(commande, StatutCommande.ANNULEE);

        if (commande.getStatut() == StatutCommande.EN_ATTENTE_PAIEMENT) {
            libererLeStock(commande);
        }
        // Une commande PAYEE annulée par un Admin exige en plus un
        // remboursement — c'est le sujet du chapitre 13.

        StatutCommande ancien = commande.getStatut();
        commande.changerStatut(StatutCommande.ANNULEE);

        journal.enregistrer("COMMANDE_ANNULER", "commande", commandeId,
                JournalActions.cliche("statut", ancien),
                JournalActions.cliche("statut", StatutCommande.ANNULEE, "motif", motif));

        // ⚠️ UN SEUL appel pour les deux publics. Cette méthode sert aussi bien
        //    l'annulation par le client que celle du back-office ; c'est
        //    l'écouteur qui écarte les acteurs internes. Poser un « si c'est un
        //    client » ici dupliquerait une règle qui vit déjà ailleurs, et les
        //    deux finiraient par diverger.
        parcours.annulation(commandeId, commande.getNumero(), motif);

        return DetailCommande.de(commande);
    }

    @Transactional
    public DetailCommande changerStatut(Long commandeId, StatutCommande nouveau) {
        if (nouveau == StatutCommande.ANNULEE) {
            return annuler(commandeId, null);
        }
        Commande commande = charger(commandeId);
        StatutCommande ancien = commande.getStatut();
        verifierTransition(commande, nouveau);
        commande.changerStatut(nouveau);

        journal.changement("COMMANDE_STATUT", "commande", commandeId,
                "statut", ancien, nouveau);

        return DetailCommande.de(commande);
    }

    /**
     * Le travail périodique qui libère les commandes jamais payées.
     *
     * <p><b>Sans lui, le stock disponible fond sans que personne ne
     * comprenne.</b> Chaque client qui abandonne son paiement mobile
     * immobilise sa marchandise définitivement.</p>
     *
     * <p>Ce n'est pas une optimisation : c'est la contrepartie obligatoire du
     * choix de réserver plutôt que décrémenter (chapitre 11 §7).</p>
     *
     * @return le nombre de commandes annulées
     */
    @Transactional
    public int libererLesImpayees() {
        Instant limite = Instant.now().minus(DELAI_PAIEMENT);

        List<Commande> expirees = commandes.findByStatutAndDateCreationBefore(
                StatutCommande.EN_ATTENTE_PAIEMENT, limite);

        for (Commande commande : expirees) {
            libererLeStock(commande);
            commande.changerStatut(StatutCommande.ANNULEE);

            // 🎯 L'acteur est « Système », et c'est une vraie réponse :
            //    « pourquoi ma commande a-t-elle été annulée ? » — parce que
            //    le paiement n'est pas arrivé dans le délai, pas parce que
            //    quelqu'un l'a décidé. Sans cette ligne, la commande porte le
            //    même statut ANNULEE qu'une annulation humaine, et plus rien
            //    ne distingue les deux.
            journal.enregistrer("COMMANDE_EXPIRER", "commande", commande.getId(),
                    JournalActions.cliche("statut", StatutCommande.EN_ATTENTE_PAIEMENT),
                    JournalActions.cliche("statut", StatutCommande.ANNULEE,
                            "motif", "Paiement non reçu dans le délai"));
        }
        return expirees.size();
    }

    private void libererLeStock(Commande commande) {
        commande.getLignes().stream()
                .sorted(Comparator.comparing(LigneCommande::getVarianteId))
                .forEach(l -> stock.liberer(l.getVarianteId(), l.getQuantite(), commande.getId()));
    }

    // -------------------------------------------------------------------------
    // Lectures
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DetailCommande detail(Long commandeId) {
        return DetailCommande.de(commandes.chargerAvecLignes(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId)));
    }

    /**
     * Le détail d'une commande, vérifié comme appartenant au client.
     *
     * <p>⚠️ Un client n'a <b>aucune permission</b> (chapitre 08 §7.2) : son
     * accès repose sur la <b>propriété</b> de ses données. C'est un mécanisme
     * différent, et il doit être vérifié explicitement à chaque lecture.</p>
     *
     * <p>Le message est celui d'une commande <b>introuvable</b>, jamais
     * « interdit » : répondre 403 confirmerait que la commande existe.</p>
     */
    @Transactional(readOnly = true)
    public DetailCommande detailPourClient(Long commandeId, Long clientId) {
        Commande commande = commandes.chargerAvecLignes(commandeId)
                .filter(c -> c.getClientId().equals(clientId))
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        return DetailCommande.de(commande);
    }

    @Transactional(readOnly = true)
    public Page<DetailCommande> mesCommandes(Long clientId, Pageable pagination) {
        return commandes.findByClientIdOrderByDateCreationDesc(clientId, pagination)
                .map(DetailCommande::de);
    }

    /**
     * La liste du back-office : toutes les commandes, filtrables.
     *
     * <p>🎯 <b>Le nom du client est résolu en UNE requête pour toute la
     * page.</b> La version naturelle — lire le client dans le {@code map} —
     * en ferait vingt-cinq pour vingt-cinq lignes. Invisible en développement
     * avec trois commandes ; très visible sur une base distante.</p>
     */
    @Transactional(readOnly = true)
    public Page<ResumeCommande> administration(StatutCommande statut, String recherche,
                                               Pageable pagination) {
        String filtre = (recherche == null || recherche.isBlank()) ? null : recherche.strip();
        Page<Commande> page = commandes.administration(statut, filtre, pagination);

        Set<Long> idsClients = page.getContent().stream()
                .map(Commande::getClientId)
                .collect(Collectors.toSet());

        Map<Long, NomClient> noms = clients.nomsPar(idsClients);

        return page.map(c -> ResumeCommande.de(c, noms.get(c.getClientId())));
    }

    private Commande charger(Long commandeId) {
        return commandes.chargerAvecLignes(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));
    }

    private void verifierTransition(Commande commande, StatutCommande vers) {
        if (!TRANSITIONS.get(commande.getStatut()).contains(vers)) {
            throw ConflitEtat.transitionInterdite(
                    "la commande " + commande.getNumero(), commande.getStatut().name(), vers.name());
        }
    }
}
