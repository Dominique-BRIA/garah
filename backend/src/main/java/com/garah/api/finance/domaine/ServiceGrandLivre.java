package com.garah.api.finance.domaine;

import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.finance.infra.EcritureMarchandRepository;
import com.garah.api.marchand.domaine.Marchand;
import com.garah.api.marchand.infra.MarchandRepository;
import com.garah.api.finance.infra.ReglementMarchandRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Year;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Le grand livre marchand.
 *
 * <p>Tout ce module repose sur une seule idée, posée au chapitre 02 :</p>
 *
 * <blockquote>
 * Tout montant dû est <b>prouvable ligne par ligne</b>, ou il est faux.
 * </blockquote>
 */
@Service
public class ServiceGrandLivre {

    private static final String DEVISE = "XAF";

    private final EcritureMarchandRepository ecritures;
    private final ReglementMarchandRepository reglements;

    /**
     * Pour LISTER les marchands avec leur solde, jamais pour les modifier.
     *
     * <p>La direction finance → marchand est libre : marchand ne connait pas
     * finance, donc aucun cycle. C est le cas le plus simple des trois qu on a
     * rencontres — ailleurs il a fallu un evenement ou du HQL seul.</p>
     */
    private final MarchandRepository marchands;

    private final JournalActions journal;

    public ServiceGrandLivre(EcritureMarchandRepository ecritures,
                             ReglementMarchandRepository reglements,
                             MarchandRepository marchands,
                             JournalActions journal) {
        this.ecritures = ecritures;
        this.reglements = reglements;
        this.marchands = marchands;
        this.journal = journal;
    }

    /**
     * Enregistre une vente : ce qu'on doit au marchand, moins la commission.
     *
     * <p><b>Deux écritures, jamais une.</b> On pourrait écrire directement le
     * net (150 000 − 15 000 = 135 000), mais on perdrait la capacité de
     * répondre à « combien de commission avez-vous prélevé ce mois-ci ? » —
     * qui est exactement la question qu'un marchand pose.</p>
     *
     * <p>⚠️ <b>Idempotent</b>, et ce n'est pas facultatif : cette méthode est
     * appelée à la confirmation du paiement, donc depuis un webhook qui
     * <b>est rejoué</b> (chapitre 13). Sans cette garde, un marchand serait
     * payé deux fois pour une seule vente.</p>
     */
    @Transactional
    public void enregistrerVente(Long marchandId, Long ligneCommandeId,
                                 BigDecimal montantLigne, BigDecimal montantCommission,
                                 String libelle) {
        if (ecritures.existsByOrigineTypeAndOrigineId(
                OrigineEcriture.LIGNE_COMMANDE, ligneCommandeId)) {
            return;
        }

        ecritures.save(new EcritureMarchand(marchandId, TypeEcriture.VENTE, montantLigne,
                OrigineEcriture.LIGNE_COMMANDE, ligneCommandeId, libelle, null));

        if (montantCommission.signum() > 0) {
            ecritures.save(new EcritureMarchand(marchandId, TypeEcriture.COMMISSION,
                    montantCommission, OrigineEcriture.LIGNE_COMMANDE, ligneCommandeId,
                    "Commission sur " + libelle, null));
        }
    }

    /**
     * Enregistre un retour : il annule la vente <b>et</b> la commission.
     *
     * <p>C'est le point qu'on oublie une fois sur deux. Si on n'annulait que
     * la vente, l'entreprise garderait une commission sur une marchandise
     * qu'elle a rendue — ce qui est indéfendable devant le marchand.</p>
     */
    @Transactional
    public void enregistrerRetour(Long marchandId, Long ligneRetourId,
                                  BigDecimal montantRembourse, BigDecimal commissionAnnulee,
                                  String libelle) {
        if (ecritures.existsByOrigineTypeAndOrigineId(
                OrigineEcriture.LIGNE_RETOUR, ligneRetourId)) {
            return;
        }

        ecritures.save(new EcritureMarchand(marchandId, TypeEcriture.RETOUR, montantRembourse,
                OrigineEcriture.LIGNE_RETOUR, ligneRetourId, libelle, null));

        if (commissionAnnulee.signum() > 0) {
            ecritures.save(new EcritureMarchand(marchandId, TypeEcriture.ANNUL_COMMISSION,
                    commissionAnnulee, OrigineEcriture.LIGNE_RETOUR, ligneRetourId,
                    "Annulation de commission sur " + libelle, null));
        }
    }

    /**
     * Le solde dû au marchand.
     *
     * <p>Recalculé à chaque appel. Il n'est stocké nulle part, donc il ne peut
     * pas désynchroniser.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal solde(Long marchandId) {
        return ecritures.solde(marchandId, DEVISE);
    }

    /**
     * Le détail qui justifie le solde.
     *
     * <p>C'est la réponse à « pourquoi doit-on 1 250 000 FCFA au marchand
     * ABC ? ». Le modèle initial ne savait pas y répondre : il n'avait qu'un
     * montant.</p>
     */
    @Transactional(readOnly = true)
    public Page<EcritureMarchand> detail(Long marchandId, Pageable pagination) {
        return ecritures.findByMarchandIdOrderByDateEcritureDesc(marchandId, pagination);
    }

    /**
     * Qui doit-on payer, et combien.
     *
     * <p>C'est la question qu'on se pose en début de mois, et rien n'y
     * répondait : il fallait connaître un marchand pour demander son solde,
     * donc les parcourir un par un.</p>
     *
     * <h2>Deux requêtes, quelle que soit la page</h2>
     *
     * <p>Une page de marchands, puis <b>tous leurs soldes d'un coup</b>.
     * Appeler {@link #solde} par ligne ferait vingt-six requêtes pour
     * vingt-cinq marchands — invisible en local avec trois lignes, très
     * visible sur base distante.</p>
     *
     * <p>⚠️ Un marchand sans aucune écriture n'apparaît pas dans l'agrégat :
     * il n'a rien vendu. Son solde vaut <b>zéro</b>, pas « inconnu », et c'est
     * ici qu'on le complète — sinon il disparaîtrait d'une liste qui prétend
     * montrer tous les marchands.</p>
     */
    @Transactional(readOnly = true)
    public Page<SoldeMarchand> soldes(Pageable pagination) {
        Page<Marchand> page = marchands.findAll(pagination);

        if (page.isEmpty()) {
            return page.map(m -> null);
        }

        Map<Long, BigDecimal> parMarchand = ecritures
                .soldesPar(page.map(Marchand::getId).toList(), DEVISE)
                .stream()
                .collect(Collectors.toMap(l -> (Long) l[0], l -> (BigDecimal) l[1]));

        return page.map(m -> new SoldeMarchand(
                m.getId(), m.getCode(), m.getNom(), m.getStatut().name(),
                parMarchand.getOrDefault(m.getId(), BigDecimal.ZERO), DEVISE));
    }

    // -------------------------------------------------------------------------
    // Règlements
    // -------------------------------------------------------------------------

    /**
     * Prépare un règlement, sans encore toucher au solde.
     *
     * <p>Un règlement `PREVU` n'écrit rien dans le grand livre : la dette ne
     * baisse qu'au moment où l'argent part réellement. Préparer n'est pas
     * payer.</p>
     */
    @Transactional
    public ReglementMarchand preparer(Long marchandId, BigDecimal montant,
                                      String moyen, Long creePar) {
        if (montant == null || montant.signum() <= 0) {
            throw new RegleMetierViolee("MONTANT_INVALIDE",
                    "Un règlement doit être strictement positif.");
        }

        BigDecimal du = solde(marchandId);
        if (montant.compareTo(du) > 0) {
            // On ne verse pas plus qu'on ne doit. Un versement excédentaire
            // rendrait le solde négatif : l'entreprise deviendrait créancière
            // du marchand, ce qui n'a aucun sens dans ce modèle.
            throw new RegleMetierViolee("REGLEMENT_EXCESSIF",
                    "Le montant dépasse ce qui est dû (" + du + " FCFA).");
        }

        ReglementMarchand prepare = reglements.save(new ReglementMarchand(
                genererNumero(), marchandId, montant, moyen, creePar));

        journal.creation("REGLEMENT_PREPARER", "reglement_marchand", prepare.getId(),
                JournalActions.cliche("numero", prepare.getNumero(),
                        "marchand", marchandId, "montant", montant, "moyen", moyen));

        return prepare;
    }

    /**
     * L'argent est parti : le règlement produit son écriture.
     *
     * <p>C'est <b>ici seulement</b> que la dette baisse. Les deux — le statut
     * du règlement et l'écriture — sont dans la même transaction : un
     * règlement marqué payé sans écriture laisserait une dette éternelle.</p>
     */
    @Transactional
    public ReglementMarchand confirmer(Long reglementId, String reference) {
        ReglementMarchand reglement = reglements.findById(reglementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Règlement", reglementId));

        if (reglement.estPaye()) {
            return reglement;   // idempotent
        }
        if ("ANNULE".equals(reglement.getStatut())) {
            throw new ConflitEtat("REGLEMENT_ANNULE",
                    "Ce règlement a été annulé, il ne peut plus être payé.");
        }

        reglement.payer(reference);

        ecritures.save(new EcritureMarchand(reglement.getMarchandId(), TypeEcriture.REGLEMENT,
                reglement.getMontant(), OrigineEcriture.REGLEMENT, reglement.getId(),
                "Règlement " + reglement.getNumero(), reglement.getCreePar()));

        // 🎯 LE geste où l'argent sort. La référence du virement est déjà
        //    obligatoire ; ce que le règlement ne dit pas, c'est QUI a appuyé,
        //    et c'est la première question posée quand un marchand affirme
        //    n'avoir rien reçu.
        journal.enregistrer("REGLEMENT_CONFIRMER", "reglement_marchand", reglementId, null,
                JournalActions.cliche("numero", reglement.getNumero(),
                        "marchand", reglement.getMarchandId(),
                        "montant", reglement.getMontant(),
                        "reference", reference));

        return reglement;
    }

    @Transactional
    public ReglementMarchand annuler(Long reglementId) {
        ReglementMarchand reglement = reglements.findById(reglementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Règlement", reglementId));

        if (reglement.estPaye()) {
            // Un règlement payé a produit une écriture. L'annuler par un
            // UPDATE effacerait l'histoire : il faut une écriture inverse,
            // c'est-à-dire un AJUSTEMENT explicite.
            throw new ConflitEtat("REGLEMENT_DEJA_PAYE",
                    "Un règlement payé s'annule par une écriture d'ajustement, pas en le modifiant.");
        }

        reglement.annuler();

        journal.geste("REGLEMENT_ANNULER", "reglement_marchand", reglementId);

        return reglement;
    }

    /**
     * La seule façon de corriger une erreur.
     *
     * <p>Le motif est obligatoire : une écriture d'ajustement sans
     * justification est indiscernable d'un détournement.</p>
     */
    @Transactional
    public EcritureMarchand ajuster(Long marchandId, BigDecimal montantSigne,
                                    String motif, Long creePar) {
        if (motif == null || motif.isBlank()) {
            throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
                    "Une écriture d'ajustement doit être justifiée.");
        }
        if (montantSigne == null || montantSigne.signum() == 0) {
            throw new RegleMetierViolee("MONTANT_INVALIDE",
                    "Un ajustement de zéro n'apprend rien à personne.");
        }

        EcritureMarchand ajustement = ecritures.save(EcritureMarchand.ajustement(
                marchandId, montantSigne, motif, creePar));

        // ⚠️ Une écriture d'ajustement sans justification est indiscernable
        //    d'un détournement. Le motif est exigé au-dessus ; il est recopié
        //    ici parce que le grand livre, lui, se corrige par une écriture de
        //    plus — et qu'on veut pouvoir remonter la série.
        journal.creation("ECRITURE_AJUSTER", "ecriture_marchand", ajustement.getId(),
                JournalActions.cliche("marchand", marchandId,
                        "montant", montantSigne, "motif", motif));

        return ajustement;
    }

    private String genererNumero() {
        return "REG-%d-%06d".formatted(Year.now().getValue(), reglements.prochainNumero());
    }
}
