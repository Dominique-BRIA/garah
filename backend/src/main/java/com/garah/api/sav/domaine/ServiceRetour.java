package com.garah.api.sav.domaine;

import com.garah.api.commerce.domaine.LigneCommande;
import com.garah.api.commerce.domaine.MoyenPaiement;
import com.garah.api.commerce.domaine.ServicePaiement;
import com.garah.api.commerce.infra.LigneCommandeRepository;
import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.commerce.domaine.NumeroCommande;
import com.garah.api.commerce.infra.CommandeRepository;
import com.garah.api.finance.domaine.ServiceGrandLivre;
import com.garah.api.iam.domaine.NomClient;
import com.garah.api.iam.domaine.ServiceClient;
import com.garah.api.sav.infra.RetourRepository;
import com.garah.api.stock.domaine.ServiceStock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Les retours de marchandise.
 *
 * <p>La correction A4 disait : <b>« un retour est une opération, pas un
 * post-it »</b>. Ce service en est la démonstration — sa validation écrit dans
 * quatre domaines, en une seule transaction.</p>
 */
@Service
public class ServiceRetour {

    /** Transitions autorisées (chapitre 04 §4.4). */
    private static final Map<StatutRetour, Set<StatutRetour>> TRANSITIONS = Map.of(
            StatutRetour.DEMANDE,     Set.of(StatutRetour.ACCEPTE, StatutRetour.REFUSE),
            StatutRetour.ACCEPTE,     Set.of(StatutRetour.RECEPTIONNE, StatutRetour.REFUSE),
            StatutRetour.RECEPTIONNE, Set.of(StatutRetour.VALIDE),
            StatutRetour.VALIDE,      Set.of(StatutRetour.CLOTURE),
            StatutRetour.REFUSE,      Set.of(),
            StatutRetour.CLOTURE,     Set.of());

    private final RetourRepository retours;
    private final LigneCommandeRepository lignesCommande;
    private final ServiceStock stock;
    private final ServicePaiement paiements;
    private final ServiceGrandLivre grandLivre;

    /**
     * Pour <b>nommer</b> le client et la commande dans les listes, jamais pour
     * les modifier.
     */
    private final ServiceClient clients;
    private final CommandeRepository commandes;

    /**
     * ⚠️ {@code retour} porte un statut, pas un parcours.
     *
     * <p>Les cinq étapes — accepté, reçu, contrôlé, validé, clôturé — écrasent
     * la précédente dans la même colonne. Qui a accepté un retour que le
     * contrôle a ensuite démenti, personne ne peut le dire une fois le statut
     * avancé. C'est le trou que ce journal comble.</p>
     */
    private final JournalActions journal;

    public ServiceRetour(RetourRepository retours, LigneCommandeRepository lignesCommande,
                         ServiceStock stock, ServicePaiement paiements,
                         ServiceGrandLivre grandLivre, ServiceClient clients,
                         CommandeRepository commandes, JournalActions journal) {
        this.journal = journal;
        this.retours = retours;
        this.lignesCommande = lignesCommande;
        this.stock = stock;
        this.paiements = paiements;
        this.grandLivre = grandLivre;
        this.clients = clients;
        this.commandes = commandes;
    }

    /**
     * Le client demande à retourner des articles.
     *
     * <p>À ce stade, <b>rien</b> n'est écrit ailleurs : ni stock, ni
     * remboursement. Un retour demandé n'est qu'une intention — la marchandise
     * n'est même pas encore revenue.</p>
     *
     * <p>Le trigger {@code ligne_retour_quantite_trigger} (I-40) empêche de
     * retourner plus qu'on n'a acheté, en tenant compte des retours
     * <b>précédents</b> : un client peut renvoyer 2 articles en mars et 2 en
     * avril sur les 3 achetés — le second sera refusé par la base.</p>
     */
    @Transactional
    public Retour demander(Long commandeId, Long clientId, String motif,
                           List<DemandeLigne> lignes) {
        if (lignes == null || lignes.isEmpty()) {
            throw new RegleMetierViolee("RETOUR_VIDE",
                    "Un retour doit porter sur au moins un article.");
        }

        verifierDejaRemis(commandeId, lignes);

        Retour retour = new Retour(genererNumero(), commandeId, clientId, motif);
        retours.save(retour);

        for (DemandeLigne demande : lignes) {
            LigneCommande ligne = lignesCommande.findById(demande.ligneCommandeId())
                    .orElseThrow(() -> RessourceIntrouvable.de(
                            "Ligne de commande", demande.ligneCommandeId()));

            if (!ligne.getCommande().getId().equals(commandeId)) {
                // Sans ce contrôle, un client pourrait retourner l'article
                // d'une commande qui n'est pas la sienne.
                throw new RegleMetierViolee("LIGNE_ETRANGERE",
                        "Cette ligne n'appartient pas à la commande indiquée.");
            }
            if (demande.quantite() < 1) {
                throw new RegleMetierViolee("QUANTITE_INVALIDE",
                        "Une ligne de retour porte sur au moins une unité.");
            }

            retour.ajouterLigne(demande.ligneCommandeId(), demande.quantite(), demande.etat());
        }

        return retour;
    }

    @Transactional
    public Retour accepter(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.ACCEPTE);
        retour.accepter();
        journal.geste("RETOUR_ACCEPTER", "retour", retourId);
        return retour;
    }

    @Transactional
    public Retour refuser(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.REFUSE);
        retour.refuser();
        // Un refus revient. Une acceptation, presque jamais.
        journal.geste("RETOUR_REFUSER", "retour", retourId);
        return retour;
    }

    /** Les articles sont physiquement revenus. Toujours rien en stock. */
    @Transactional
    public Retour receptionner(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.RECEPTIONNE);
        retour.receptionner();
        // ⚠️ « Accepté » n'est pas « reçu ». Confondre les deux, c'est
        //    rembourser une marchandise qui n'est jamais rentrée — d'où une
        //    ligne distincte pour chacune des deux étapes.
        journal.geste("RETOUR_RECEPTIONNER", "retour", retourId);
        return retour;
    }

    /**
     * <b>L'opération centrale du module</b>, et l'exemple du chapitre 04 §11.
     *
     * <p>Elle écrit dans quatre domaines, en une seule transaction :</p>
     *
     * <pre>
     * 1. le retour passe à VALIDE
     * 2. pour chaque ligne, un MOUVEMENT DE STOCK
     *       NEUF  → compteur DISPONIBLE   (revendable)
     *       ABIMÉ → compteur ENDOMMAGEE   (jamais revendu)
     * 3. le montant remboursé est calculé et figé sur chaque ligne
     * 4. un PAIEMENT de type REMBOURSEMENT est créé
     * 5. les ÉCRITURES MARCHAND : la vente ET la commission sont annulées
     * </pre>
     *
     * <p>⚠️ <b>Si l'une échoue, aucune ne doit rester.</b> Un retour à moitié
     * enregistré — stock remis mais client non remboursé — est pire que pas de
     * retour du tout, parce que personne ne s'en apercevra.</p>
     *
     * <p>Et c'est seulement <b>ici</b> que le remboursement part : après que
     * quelqu'un a vu et contrôlé la marchandise. Jamais à la demande.</p>
     */
    @Transactional
    public Retour valider(Long retourId, MoyenPaiement moyen) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.VALIDE);

        BigDecimal totalARembourser = BigDecimal.ZERO;

        for (LigneRetour ligneRetour : retour.getLignes()) {
            LigneCommande ligneCommande = lignesCommande.findById(ligneRetour.getLigneCommandeId())
                    .orElseThrow(() -> RessourceIntrouvable.de(
                            "Ligne de commande", ligneRetour.getLigneCommandeId()));

            // 2. Le mouvement de stock dépend de l'ÉTAT de l'article.
            stock.retour(ligneCommande.getVarianteId(), ligneRetour.getQuantite(),
                    retour.getId(), ligneRetour.getEtatArticle().estRevendable());

            // 3. On rembourse au prix RÉELLEMENT PAYÉ, pas au prix du jour.
            //    C'est la règle de la photographie qui rend ce calcul possible.
            BigDecimal montant = ligneCommande.getPrixUnitaire()
                    .multiply(BigDecimal.valueOf(ligneRetour.getQuantite()))
                    .setScale(2, RoundingMode.HALF_UP);

            ligneRetour.definirRemboursement(montant);
            totalARembourser = totalARembourser.add(montant);

            // 5. Le grand livre marchand (chapitre 17).
            //
            // Un retour annule DEUX écritures, pas une : on ne doit plus la
            // vente au marchand, mais on ne garde pas non plus la commission
            // prélevée dessus. Garder la commission sur une marchandise rendue
            // est indéfendable devant le partenaire.
            BigDecimal commissionAnnulee = montant
                    .multiply(ligneCommande.getTauxCommission())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            grandLivre.enregistrerRetour(ligneCommande.getMarchandId(), ligneRetour.getId(),
                    montant, commissionAnnulee,
                    "Retour " + retour.getNumero() + " — " + ligneCommande.getDesignation());
        }

        // 4. Un seul remboursement pour tout le retour : le client reçoit un
        //    virement, pas quatre.
        if (totalARembourser.signum() > 0) {
            paiements.rembourser(retour.getCommandeId(), totalARembourser, moyen,
                    "RETOUR", retour.getId());
        }

        retour.valider();

        // 🎯 LE geste qui fait sortir de l'argent vers un client, après
        //    contrôle physique de la marchandise. C'est celui qu'on voudra
        //    relire en premier si un remboursement est contesté — ou s'il ne
        //    l'est pas et qu'il aurait dû l'être.
        journal.enregistrer("RETOUR_VALIDER", "retour", retourId, null,
                JournalActions.cliche("numero", retour.getNumero(),
                        "commande", retour.getCommandeId(),
                        "montantRembourse", totalARembourser,
                        "moyen", moyen));

        return retour;
    }

    @Transactional
    public Retour cloturer(Long retourId) {
        Retour retour = charger(retourId);
        verifierTransition(retour, StatutRetour.CLOTURE);
        retour.cloturer();
        journal.geste("RETOUR_CLOTURER", "retour", retourId);
        return retour;
    }

    /**
     * Mes retours.
     *
     * <p>Le client suit l'argent qu'il attend. Sans cet écran, une fois la
     * demande envoyée il n'avait <b>plus rien</b> : ni numéro, ni statut, ni
     * moyen de savoir si le colis avait été reçu — seulement l'attente, qui
     * produit exactement les réclamations qu'on cherche à éviter.</p>
     *
     * <p>⚠️ Le filtre est le {@code clientId}, jamais un paramètre de requête.
     * Une liste « mienne » filtrée par un identifiant reçu du navigateur
     * n'est pas une liste mienne : c'est la liste de qui veut bien
     * l'écrire.</p>
     */
    @Transactional(readOnly = true)
    public Page<VueRetour> mesRetours(Long clientId, Pageable pagination) {
        // ⚠️ VueRetour.resume et non complete : getLignes() est paresseux, et
        //    une page de vingt retours ferait vingt requêtes de plus. La liste
        //    n'affiche pas le détail des lignes — la fiche, si.
        return retours.findByClientIdOrderByDateCreationDesc(clientId, pagination)
                .map(VueRetour::resume);
    }

    @Transactional(readOnly = true)
    public List<Retour> pourCommande(Long commandeId) {
        return retours.findByCommandeId(commandeId);
    }

    /**
     * La liste du back-office, clients et commandes <b>nommés</b>.
     *
     * <p>Quatre requêtes en tout, quelle que soit la taille de la page : les
     * retours, leurs clients, leurs numéros de commande, et les totaux de
     * leurs lignes. Compter les articles retour par retour ferait cinquante
     * requêtes de plus pour vingt-cinq lignes.</p>
     *
     * <p>⚠️ Le total des <b>articles annoncés</b> et le total <b>remboursé</b>
     * viennent de la même requête mais ne veulent pas dire la même chose :
     * l'un est déclaré par le client, l'autre constaté après ouverture du
     * colis. Voir {@link ResumeRetour}.</p>
     */
    @Transactional(readOnly = true)
    public Page<ResumeRetour> administration(StatutRetour statut, Pageable pagination) {
        Page<Retour> page = retours.administration(statut, pagination);

        if (page.isEmpty()) {
            // `IN ()` avec une collection vide est refusé par certains
            // dialectes : on ne pose pas la question quand il n'y a rien à
            // demander.
            return page.map(r -> ResumeRetour.de(r, null, null, 0, BigDecimal.ZERO));
        }

        List<Long> ids = page.map(Retour::getId).toList();

        Map<Long, NomClient> noms = clients.nomsPar(page.map(Retour::getClientId).toList());

        Map<Long, String> numeros = commandes.numerosPar(page.map(Retour::getCommandeId).toList())
                .stream()
                .collect(Collectors.toMap(NumeroCommande::id, NumeroCommande::numero));

        Map<Long, Object[]> totaux = retours.totauxPar(ids).stream()
                .collect(Collectors.toMap(ligne -> (Long) ligne[0], ligne -> ligne));

        return page.map(r -> {
            Object[] total = totaux.get(r.getId());
            return ResumeRetour.de(r, noms.get(r.getClientId()),
                    numeros.get(r.getCommandeId()),
                    total == null ? 0 : ((Number) total[1]).longValue(),
                    total == null ? BigDecimal.ZERO : (BigDecimal) total[2]);
        });
    }

    /** Ce que le client demande de retourner. */
    public record DemandeLigne(Long ligneCommandeId, int quantite, EtatArticle etat) {
    }

    private String genererNumero() {
        return "RET-%d-%06d".formatted(Year.now().getValue(), retours.prochainNumero());
    }

    private Retour charger(Long retourId) {
        return retours.chargerAvecLignes(retourId)
                .orElseThrow(() -> RessourceIntrouvable.de("Retour", retourId));
    }

    private void verifierTransition(Retour retour, StatutRetour vers) {
        if (!TRANSITIONS.get(retour.getStatut()).contains(vers)) {
            throw ConflitEtat.transitionInterdite(
                    "le retour " + retour.getNumero(), retour.getStatut().name(), vers.name());
        }
    }

    /** Un retour avec ses lignes. Converti dans la transaction. */
    @Transactional(readOnly = true)
    public VueRetour vue(Long retourId) {
        return VueRetour.complete(retours.findById(retourId)
                .orElseThrow(() -> RessourceIntrouvable.de("Retour", retourId)));
    }

    /**
     * On ne retourne que ce qu'on a reçu.
     *
     * <h2>🎯 Le défaut que ceci ferme</h2>
     *
     * <p>Rien n'empêchait un client de demander le retour d'articles qu'il
     * n'avait pas encore récupérés — voire qui n'étaient pas encore partis.
     * Le trigger I-40 compare au COMMANDÉ, pas au REMIS.</p>
     *
     * <p>Les quantités d'une même ligne sont additionnées sur toute la
     * demande : deux fois 3 unités de la même ligne en font 6.</p>
     *
     * <p>⚠️ Seules les lignes de CETTE commande sont vérifiées ici. Une ligne
     * étrangère est refusée plus loin, comme telle — la vérifier ici
     * donnerait à lire la désignation de l'article de quelqu'un d'autre.</p>
     */
    private void verifierDejaRemis(Long commandeId, List<DemandeLigne> lignes) {
        Map<Long, Integer> demandees = new java.util.HashMap<>();
        lignes.forEach(d -> demandees.merge(d.ligneCommandeId(), Math.max(d.quantite(), 0), Integer::sum));

        for (Map.Entry<Long, Integer> demande : demandees.entrySet()) {
            LigneCommande ligne = lignesCommande.findById(demande.getKey()).orElse(null);
            if (ligne == null || !ligne.getCommande().getId().equals(commandeId)) {
                continue;
            }
            long remis = retours.quantiteRemise(ligne.getId());
            long deja = retours.quantiteDejaRetournee(ligne.getId());
            if (deja + demande.getValue() > remis) {
                throw new RegleMetierViolee("ARTICLE_NON_REMIS", remis == 0
                        ? "« " + ligne.getDesignation() + " » ne vous a pas encore été remis : "
                          + "il ne peut pas être retourné."
                        : "Au plus " + Math.max(remis - deja, 0) + " unité(s) de « "
                          + ligne.getDesignation() + " » peuvent encore être retournées.");
            }
        }
    }
}
