package com.garah.api.commerce.domaine;

import com.garah.api.catalogue.domaine.InfoVenteVariante;
import com.garah.api.catalogue.domaine.ServiceTarification;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commerce.infra.CommandeRepository;
import com.garah.api.commerce.infra.PanierRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
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

    public ServiceCommande(CommandeRepository commandes, PanierRepository paniers,
                           VarianteRepository variantes, ServiceTarification tarification,
                           ServiceCommission commissions, ServiceStock stock,
                           LieuRepository lieux) {
        this.commandes = commandes;
        this.paniers = paniers;
        this.variantes = variantes;
        this.tarification = tarification;
        this.commissions = commissions;
        this.stock = stock;
        this.lieux = lieux;
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

        commande.changerStatut(StatutCommande.ANNULEE);
        return DetailCommande.de(commande);
    }

    @Transactional
    public DetailCommande changerStatut(Long commandeId, StatutCommande nouveau) {
        if (nouveau == StatutCommande.ANNULEE) {
            return annuler(commandeId, null);
        }
        Commande commande = charger(commandeId);
        verifierTransition(commande, nouveau);
        commande.changerStatut(nouveau);
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
