package com.garah.api.commerce.domaine;

import com.garah.api.commerce.infra.CommandeRepository;
import com.garah.api.commerce.infra.PaiementRepository;
import com.garah.api.commerce.infra.TentativePaiementRepository;
import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.finance.domaine.ServiceGrandLivre;
import com.garah.api.stock.domaine.ServiceStock;
import com.garah.api.surveillance.domaine.GraviteEvenement;
import com.garah.api.surveillance.domaine.ServiceEvenementsSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Le paiement mobile money — et son principal piège : <b>l'asynchronisme</b>.
 *
 * <pre>
 * 1. le client demande à payer            → INITIE
 * 2. l'opérateur envoie un code au client → EN_ATTENTE
 * 3. le client valide sur son téléphone
 * 4. l'opérateur appelle NOTRE webhook    → CONFIRME
 * </pre>
 *
 * <p>Entre 1 et 4, il peut s'écouler plusieurs minutes. C'est toute la raison
 * d'être de la réservation de stock (chapitre 11).</p>
 */
@Service
public class ServicePaiement {

    private final PaiementRepository paiements;
    private final TentativePaiementRepository tentatives;
    private final CommandeRepository commandes;
    private final ServiceStock stock;
    private final ServiceEvenementsSecurite securite;
    private final ServiceGrandLivre grandLivre;

    /**
     * ⚠️ Un ENCAISSEMENT n'est pas journalisé ici : c'est le client qui paie,
     * et c'est l'opérateur mobile qui confirme. Un REMBOURSEMENT, lui, est
     * toujours décidé chez nous — et fait sortir de l'argent.
     */
    private final JournalActions journal;

    public ServicePaiement(PaiementRepository paiements, TentativePaiementRepository tentatives,
                           CommandeRepository commandes, ServiceStock stock,
                           ServiceEvenementsSecurite securite,
                           ServiceGrandLivre grandLivre,
                           JournalActions journal) {
        this.journal = journal;
        this.paiements = paiements;
        this.tentatives = tentatives;
        this.commandes = commandes;
        this.stock = stock;
        this.securite = securite;
        this.grandLivre = grandLivre;
    }

    /**
     * Démarre un paiement pour la totalité du montant restant dû.
     *
     * <p>Le montant n'est <b>jamais</b> fourni par le client : il est lu sur la
     * commande. Accepter un montant venu de l'extérieur permettrait de payer
     * 100 FCFA une commande de 100 000 — c'est la faille la plus classique
     * d'un tunnel de paiement.</p>
     */
    @Transactional
    public Paiement initier(Long commandeId, MoyenPaiement moyen) {
        Commande commande = commandes.findById(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        if (commande.getStatut() != StatutCommande.EN_ATTENTE_PAIEMENT) {
            throw new ConflitEtat("COMMANDE_NON_PAYABLE",
                    "Cette commande n'attend plus de paiement.");
        }

        BigDecimal dejaPaye = totalConfirme(commandeId, TypePaiement.ENCAISSEMENT);
        BigDecimal reste = commande.getMontantTotal().subtract(dejaPaye);

        if (reste.signum() <= 0) {
            throw new ConflitEtat("DEJA_PAYEE", "Cette commande est déjà réglée.");
        }

        return paiements.save(Paiement.encaissement(commandeId, reste, moyen));
    }

    /**
     * Confirme un paiement — appelé par le webhook de l'opérateur.
     *
     * <p><b>Cette méthode DOIT être idempotente.</b> MTN et Orange rejouent la
     * notification tant qu'ils n'ont pas d'accusé de réception : timeout
     * réseau, redémarrage du serveur, instance Render qui sortait de veille.
     * <b>Deux appels pour le même paiement sont la norme, pas l'exception.</b></p>
     *
     * <p>Sans cette garde, une confirmation reçue deux fois produirait :</p>
     * <ul>
     *   <li>deux sorties de stock pour une seule commande ;</li>
     *   <li>deux ventes au marchand ;</li>
     *   <li>un solde faux — <b>sans aucune erreur visible</b>.</li>
     * </ul>
     *
     * <p>L'index unique {@code paiement_reference_unique} (V18) est la seconde
     * ligne de défense : même un import manuel ne pourrait pas enregistrer
     * deux fois la même transaction opérateur.</p>
     */
    @Transactional
    public Paiement confirmer(Long paiementId, String referenceTransaction) {
        Paiement paiement = paiements.findById(paiementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Paiement", paiementId));

        // ⚠️ LE point d'idempotence. On sort SANS erreur : le webhook a bien
        // fait son travail, il ne doit surtout pas réessayer indéfiniment.
        if (paiement.estConfirme()) {
            return paiement;
        }

        if (paiement.getStatut() == StatutPaiement.ECHOUE
                || paiement.getStatut() == StatutPaiement.ANNULE) {
            throw new ConflitEtat("PAIEMENT_CLOS",
                    "Ce paiement est déjà clos, il ne peut plus être confirmé.");
        }

        paiement.confirmer(referenceTransaction);
        tentatives.save(new TentativePaiement(paiement.getId(), "CONFIRME", null, null));

        if (paiement.getType() == TypePaiement.ENCAISSEMENT) {
            encaisserSurLaCommande(paiement.getCommandeId());
        }

        return paiement;
    }

    /**
     * Fait basculer la commande si elle est entièrement réglée.
     *
     * <p>Trois effets dans <b>la même transaction</b> : le statut de la
     * commande, la sortie de stock, et les écritures du grand livre marchand.
     * Ce qui doit être vrai ensemble s'écrit ensemble.</p>
     */
    private void encaisserSurLaCommande(Long commandeId) {
        Commande commande = commandes.chargerAvecLignes(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        if (commande.getStatut() != StatutCommande.EN_ATTENTE_PAIEMENT) {
            return;   // déjà basculée : rien à faire, et surtout pas d'erreur
        }

        BigDecimal encaisse = totalConfirme(commandeId, TypePaiement.ENCAISSEMENT);
        if (encaisse.compareTo(commande.getMontantTotal()) < 0) {
            return;   // paiement partiel : on attend le reste
        }

        commande.changerStatut(StatutCommande.PAYEE);

        // La marchandise quitte enfin le stock réservé. Ordre trié : deux
        // commandes des mêmes articles ne doivent pas s'interbloquer.
        commande.getLignes().stream()
                .sorted(Comparator.comparing(LigneCommande::getVarianteId))
                .forEach(l -> stock.confirmerSortie(l.getVarianteId(), l.getQuantite(), commandeId));

        // Le grand livre marchand (chapitre 17). C'est le bon moment : le
        // paiement est acquis, donc la dette envers le marchand est née.
        //
        // Le service est idempotent par ligne de commande — indispensable,
        // puisqu'on est appelé depuis un webhook qui peut être rejoué.
        commande.getLignes().forEach(ligne ->
                grandLivre.enregistrerVente(
                        ligne.getMarchandId(), ligne.getId(),
                        ligne.getMontantLigne(), ligne.getMontantCommission(),
                        "Commande " + commande.getNumero() + " — " + ligne.getDesignation()));
    }

    /**
     * Enregistre un échec.
     *
     * <p>L'échec est <b>conservé</b> : il alimente le score de risque (§19).
     * Et il produit un événement de sécurité, écrit en {@code REQUIRES_NEW}
     * pour survivre à l'annulation éventuelle de la transaction appelante
     * (chapitre 08 §9).</p>
     */
    @Transactional
    public Paiement echouer(Long paiementId, String codeErreur, String message, Long clientId) {
        Paiement paiement = paiements.findById(paiementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Paiement", paiementId));

        if (paiement.estConfirme()) {
            // Un échec après une confirmation est suspect : on refuse, et la
            // trace reste. Accepter reviendrait à « déconfirmer » un paiement.
            throw new ConflitEtat("PAIEMENT_DEJA_CONFIRME",
                    "Ce paiement est déjà confirmé.");
        }

        paiement.echouer();
        tentatives.save(new TentativePaiement(paiement.getId(), "ECHOUE", codeErreur, message));

        securite.enregistrer(clientId, TypeEvenementSecurite.ECHEC_PAIEMENT,
                GraviteEvenement.FAIBLE, null,
                "Paiement " + paiement.getId() + " : " + codeErreur);

        return paiement;
    }

    /**
     * Rembourse tout ou partie d'une commande.
     *
     * <p>Un remboursement est un {@link Paiement} de type
     * {@code REMBOURSEMENT}, avec un montant <b>positif</b> — le sens est
     * porté par le type. Il doit toujours dire ce qui le justifie
     * ({@code origineType}), sinon la contrainte
     * {@code paiement_remboursement_justifie} le refuse.</p>
     */
    @Transactional
    public Paiement rembourser(Long commandeId, BigDecimal montant, MoyenPaiement moyen,
                               String origineType, Long origineId) {
        if (montant == null || montant.signum() <= 0) {
            throw new RegleMetierViolee("MONTANT_INVALIDE",
                    "Un remboursement doit être strictement positif.");
        }
        if (origineType == null) {
            throw new RegleMetierViolee("REMBOURSEMENT_INJUSTIFIE",
                    "Un remboursement doit indiquer ce qui le justifie.");
        }

        commandes.findById(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        // I-27 : on ne rembourse jamais plus qu'on n'a encaissé.
        BigDecimal encaisse = totalConfirme(commandeId, TypePaiement.ENCAISSEMENT);
        BigDecimal dejaRembourse = totalConfirme(commandeId, TypePaiement.REMBOURSEMENT);

        if (dejaRembourse.add(montant).compareTo(encaisse) > 0) {
            throw new RegleMetierViolee("REMBOURSEMENT_EXCESSIF",
                    "Le remboursement dépasserait le montant encaissé ("
                    + encaisse.subtract(dejaRembourse) + " restant).");
        }

        Paiement remboursement = paiements.save(
                Paiement.remboursement(commandeId, montant, moyen, origineType, origineId));

        journal.creation("REMBOURSEMENT_EMETTRE", "paiement", remboursement.getId(),
                JournalActions.cliche("commande", commandeId, "montant", montant,
                        "moyen", moyen, "origine", origineType + " n° " + origineId));

        return remboursement;
    }

    /**
     * Note la référence de transaction obtenue auprès de l'opérateur.
     *
     * <p>Le paiement passe alors de {@code INITIE} à {@code EN_ATTENTE} : la
     * demande est partie, le client doit maintenant valider sur son téléphone.
     * <b>Rien n'est encaissé à ce stade.</b></p>
     *
     * <p>Sans cette référence, le paiement serait définitivement orphelin :
     * ni le webhook ni la réconciliation ne pourraient le retrouver, et un
     * client débité ne serait jamais crédité.</p>
     */
    /**
     * Rembourse, et renvoie un DTO plutôt que l'entité.
     *
     * <p>Surcouche mince sur {@link #rembourser} : la couche web n'a pas le
     * droit de toucher une entité JPA, et ArchUnit le vérifie. Les tests, eux,
     * continuent d'appeler {@code rembourser} et de raisonner sur l'entité.</p>
     */
    @Transactional
    public ResumePaiement rembourserEtResumer(Long commandeId, BigDecimal montant,
                                              MoyenPaiement moyen, String origineType,
                                              Long origineId) {
        return ResumePaiement.de(
                rembourser(commandeId, montant, moyen, origineType, origineId));
    }

    @Transactional
    public Paiement enregistrerAupresOperateur(Long paiementId, String reference) {
        Paiement paiement = paiements.findById(paiementId)
                .orElseThrow(() -> RessourceIntrouvable.de("Paiement", paiementId));

        if (paiement.getStatut() != StatutPaiement.INITIE) {
            throw new ConflitEtat("PAIEMENT_DEJA_ENGAGE",
                    "Ce paiement a déjà été transmis à l'opérateur.");
        }

        paiement.mettreEnAttente(reference);
        return paiement;
    }

    @Transactional(readOnly = true)
    public BigDecimal resteAPayer(Long commandeId) {
        Commande commande = commandes.findById(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        return commande.getMontantTotal()
                .subtract(totalConfirme(commandeId, TypePaiement.ENCAISSEMENT));
    }

    /**
     * La liste du back-office : encaissements et remboursements.
     *
     * <p>Les deux ensemble par défaut. « Qu'est-il arrivé à l'argent de cette
     * commande ? » ne se répond pas en consultant deux écrans — un
     * remboursement n'a de sens qu'en regard de l'encaissement qu'il défait.</p>
     *
     * <p>Le numéro de commande est résolu <b>en une requête pour toute la
     * page</b>, comme le nom du client dans la liste des commandes.</p>
     */
    @Transactional(readOnly = true)
    public Page<ResumePaiement> administration(StatutPaiement statut, TypePaiement type,
                                               String recherche, Pageable pagination) {
        String filtre = (recherche == null || recherche.isBlank()) ? null : recherche.strip();
        Page<Paiement> page = paiements.administration(statut, type, filtre, pagination);

        Set<Long> ids = page.getContent().stream()
                .map(Paiement::getCommandeId)
                .collect(Collectors.toSet());

        Map<Long, String> numeros = ids.isEmpty()
                // `IN ()` est une requête invalide en SQL : on ne la lance pas.
                ? Map.of()
                : commandes.numerosPar(ids).stream()
                        .collect(Collectors.toMap(NumeroCommande::id, NumeroCommande::numero));

        return page.map(p -> ResumePaiement.de(p, numeros.get(p.getCommandeId())));
    }

    /** L'historique complet d'une commande : ce qu'on a encaissé et remboursé. */
    @Transactional(readOnly = true)
    public List<ResumePaiement> pourCommande(Long commandeId) {
        return paiements.findByCommandeIdOrderByDateInitiationDesc(commandeId).stream()
                .map(ResumePaiement::de)
                .toList();
    }

    private BigDecimal totalConfirme(Long commandeId, TypePaiement type) {
        return paiements.total(commandeId, type, StatutPaiement.CONFIRME);
    }
}
