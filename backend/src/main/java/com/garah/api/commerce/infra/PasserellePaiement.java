package com.garah.api.commerce.infra;

import com.garah.api.commerce.domaine.FournisseurPaiement;
import com.garah.api.commun.erreur.ErreurMetier;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Ce que GARAH attend d'un fournisseur de paiement mobile — Campay ou
 * MoneyFusion (D-55).
 *
 * <p>Le domaine ne parle qu'à cette interface. Les deux fournisseurs ne
 * fonctionnent pourtant pas de la même façon :</p>
 *
 * <pre>
 * Campay        GARAH demande, le téléphone du client sonne      codeUssd
 * MoneyFusion   le client est envoyé sur une page de paiement    urlPaiement
 * </pre>
 *
 * <p>Une seule forme de réponse couvre les deux : chacun remplit le champ qui
 * le concerne et laisse l'autre vide. Les écrans savent quoi faire selon ce
 * qui est rempli.</p>
 */
public interface PasserellePaiement {

    FournisseurPaiement fournisseur();

    /** Les variables sont-elles posées ? Sert à répondre 503 plutôt qu'à planter. */
    boolean estConfigure();

    /** Un bac à sable n'encaisse rien de réel : les écrans doivent le dire. */
    boolean estDemonstration();

    /**
     * Demande au fournisseur d'encaisser.
     *
     * <p><b>Rien n'est encaissé à ce stade</b> : la confirmation arrivera par
     * le webhook, ou par la réconciliation périodique.</p>
     */
    Collecte encaisser(DemandeEncaissement demande);

    /**
     * L'état réel d'une transaction, demandé au fournisseur sur une connexion
     * que NOUS ouvrons. C'est la méthode qui porte toute la sécurité du
     * webhook : ce qu'une notification affirme n'est jamais cru.
     *
     * @return vide si le fournisseur ne connaît pas cette référence
     */
    Optional<EtatTransaction> statut(String reference);

    /**
     * Tout ce qu'un fournisseur peut vouloir savoir pour encaisser.
     *
     * @param paiementId notre identifiant — c'est lui qui rapproche les deux
     *                   systèmes le jour d'un litige
     * @param urlRetour  où renvoyer le client après une page de paiement.
     *                   Ignoré par un fournisseur qui n'en a pas.
     */
    record DemandeEncaissement(Long paiementId, Long commandeId, String numeroCommande,
                               BigDecimal montant, String telephone, String nomClient,
                               String urlRetour) {
    }

    /**
     * Ce que le fournisseur renvoie quand on lui demande d'encaisser.
     *
     * @param codeUssd    Campay : à composer si la demande n'arrive pas
     * @param urlPaiement MoneyFusion : la page où le client paie
     */
    record Collecte(String reference, String codeUssd, String operateur, String urlPaiement) {
    }

    /**
     * Le fournisseur a <b>répondu</b>, et il a refusé.
     *
     * <h2>🎯 Ce que cette classe sépare</h2>
     *
     * <p>Tout échec d'appel devenait « le service de paiement est
     * momentanément injoignable ». C'était faux la moitié du temps : un
     * {@code 400} signifie que l'opérateur a répondu parfaitement, pour dire
     * <b>non</b> — montant trop faible, numéro invalide, opérateur qui ne
     * correspond pas au préfixe.</p>
     *
     * <p>⚠️ Le coût du mélange : on cherche une panne réseau pendant que la
     * réponse était sur la table. Un paiement de 20 FCFA était refusé parce
     * que Campay exige un minimum, et l'écran annonçait une indisponibilité.</p>
     *
     * <p>{@code 422} et non {@code 503} : rien n'est en panne, et réessayer à
     * l'identique donnera le même refus.</p>
     */
    class OperateurRefuse extends ErreurMetier {
        public OperateurRefuse(String message) {
            super("OPERATEUR_REFUSE", message);
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /**
     * Le service de paiement est indisponible.
     *
     * <p>{@code 503}, pas {@code 500} : la commande du client est intacte, la
     * panne est chez nous ou chez l'opérateur, et réessayer plus tard a du
     * sens. Un {@code 500} dirait « bug », et le support chercherait au mauvais
     * endroit.</p>
     */
    class OperateurIndisponible extends ErreurMetier {
        public OperateurIndisponible(String message) {
            super("OPERATEUR_INDISPONIBLE", message);
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /** Où en est une transaction, dans un vocabulaire commun aux fournisseurs. */
    enum Issue {
        REUSSIE,
        ECHOUEE,
        EN_COURS
    }

    /**
     * L'état d'une transaction chez le fournisseur — <b>la source de vérité</b>.
     *
     * @param montant          ce qui a été encaissé, tel que le fournisseur le compte
     * @param operateur        le moyen annoncé, mot pour mot (« orange », « MTN »…)
     * @param referenceOperateur la référence chez MTN ou Orange, quand elle existe
     */
    record EtatTransaction(String reference, Issue issue, BigDecimal montant,
                           String operateur, String referenceOperateur,
                           String codeErreur) {

        public boolean reussi() {
            return issue == Issue.REUSSIE;
        }

        public boolean echoue() {
            return issue == Issue.ECHOUEE;
        }

        public boolean enCours() {
            return issue == Issue.EN_COURS;
        }
    }
}
