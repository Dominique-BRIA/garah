package com.garah.api.commerce.domaine;

import com.garah.api.commerce.infra.CommandeRepository;
import com.garah.api.commerce.infra.PaiementRepository;
import com.garah.api.commerce.infra.TentativePaiementRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.stock.domaine.ServiceStock;
import com.garah.api.surveillance.domaine.GraviteEvenement;
import com.garah.api.surveillance.domaine.ServiceEvenementsSecurite;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;

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

    public ServicePaiement(PaiementRepository paiements, TentativePaiementRepository tentatives,
                           CommandeRepository commandes, ServiceStock stock,
                           ServiceEvenementsSecurite securite) {
        this.paiements = paiements;
        this.tentatives = tentatives;
        this.commandes = commandes;
        this.stock = stock;
        this.securite = securite;
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
     * <p>Trois écritures dans <b>la même transaction</b> : le statut, la sortie
     * de stock, et — au chapitre 17 — les écritures marchand. Ce qui doit être
     * vrai ensemble s'écrit ensemble.</p>
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

        // TODO chapitre 17 : écrire ici les écritures VENTE et COMMISSION du
        // grand livre marchand. C'est le bon moment — le paiement est acquis.
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

        return paiements.save(
                Paiement.remboursement(commandeId, montant, moyen, origineType, origineId));
    }

    @Transactional(readOnly = true)
    public BigDecimal resteAPayer(Long commandeId) {
        Commande commande = commandes.findById(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        return commande.getMontantTotal()
                .subtract(totalConfirme(commandeId, TypePaiement.ENCAISSEMENT));
    }

    private BigDecimal totalConfirme(Long commandeId, TypePaiement type) {
        return paiements.total(commandeId, type, StatutPaiement.CONFIRME);
    }
}
