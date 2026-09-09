package com.garah.api.iam.domaine;

import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.infra.ClientRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * La lecture des clients, pour les domaines qui les affichent sans les gérer.
 *
 * <p>Une commande ne porte qu'un {@code clientId} : les deux domaines restent
 * séparés, et c'est voulu. Mais une liste de commandes qui n'afficherait qu'un
 * identifiant numérique serait illisible — il faut bien traverser la
 * frontière quelque part. Ici, en lecture seule, par une projection.</p>
 */
@Service
public class ServiceClient {

    private final ClientRepository clients;
    private final JournalActions journal;

    public ServiceClient(ClientRepository clients,
                         JournalActions journal) {
        this.clients = clients;
        this.journal = journal;
    }

    /**
     * De quoi désigner plusieurs clients, indexés par identifiant.
     *
     * <p>Une requête pour toute la page, pas une par ligne.</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, NomClient> nomsPar(Collection<Long> ids) {
        if (ids.isEmpty()) {
            // `IN ()` est une requête invalide en SQL : on ne la lance pas.
            return Map.of();
        }
        return clients.nomsPar(ids).stream()
                .collect(Collectors.toMap(NomClient::id, Function.identity()));
    }

    // -------------------------------------------------------------------------
    // Le back-office
    // -------------------------------------------------------------------------

    /**
     * La liste du back-office.
     *
     * <p>Le tri est <b>l'inverse</b> de celui des réclamations : les plus
     * récents d'abord. Une liste de clients répond à « qui vient de
     * s'inscrire ? », pas à « qu'est-ce qui traîne ? » — et le client qu'on
     * cherche, on le cherche par son nom, pas en descendant la page.</p>
     */
    @Transactional(readOnly = true)
    public Page<ResumeClient> administration(StatutUtilisateur statut, String recherche,
                                             Pageable pagination) {
        String filtre = (recherche == null || recherche.isBlank()) ? null : recherche.strip();
        return clients.administration(statut, filtre, pagination);
    }

    @Transactional(readOnly = true)
    public FicheClient fiche(Long clientId) {
        return FicheClient.de(charger(clientId));
    }

    /**
     * Suspend ou réactive un compte.
     *
     * <p>🎯 <b>Suspendre, jamais supprimer.</b> Un client porte des commandes,
     * des paiements, des retours, des écritures. Effacer son compte rendrait
     * inexplicables des lignes qui, elles, restent — et une facture sans
     * acheteur n'est pas une facture.</p>
     *
     * <p>⚠️ <b>Ce geste ne touche que le compte client.</b> Il ne change pas
     * le statut de l'utilisateur sous-jacent : le même humain pourrait être
     * responsable par ailleurs, et suspendre son compte d'achat n'a aucune
     * raison de lui retirer son poste. Les fondre a l'air d'une simplification
     * et ne l'est pas.</p>
     *
     * <p><b>{@code INACTIF}, et jamais {@code BLOQUE}.</b> Les deux existent
     * (V2), et ils ne disent pas la même chose : {@code INACTIF} est une
     * décision administrative — un compte en sommeil, un doublon, un client
     * qui demande la fermeture. {@code BLOQUE} est une décision de
     * <b>sécurité</b>, prise sur alerte, et elle appartient au module
     * surveillance. Les confondre ferait apparaître comme fraudeur un client
     * simplement désactivé.</p>
     */
    @Transactional
    public FicheClient activer(Long clientId, boolean actif) {
        Client client = charger(clientId);
        StatutUtilisateur ancien = client.getStatut();
        client.setStatut(actif ? StatutUtilisateur.ACTIF : StatutUtilisateur.INACTIF);

        // 🎯 Le client, lui, n'est pas l'acteur : c'est quelqu'un de la maison
        //    qui suspend son compte. Le geste est donc interne, et il se
        //    journalise — « qui m'a fermé mon compte ? » est une réclamation
        //    qui arrive, et elle doit trouver une réponse.
        journal.changement(actif ? "CLIENT_ACTIVER" : "CLIENT_SUSPENDRE",
                "client", clientId, "statut", ancien, client.getStatut());

        return FicheClient.de(client);
    }

    /**
     * Corrige les coordonnées.
     *
     * <p>Le téléphone et la langue seulement. <b>Pas l'adresse e-mail</b> :
     * elle identifie le compte, elle sert à s'y connecter, et elle a été
     * vérifiée. La changer depuis le back-office reviendrait à donner le
     * compte d'un client à quelqu'un d'autre, sans que le premier soit même
     * prévenu.</p>
     *
     * <p>Un changement d'e-mail passe par le client lui-même, avec une
     * nouvelle vérification.</p>
     */
    @Transactional
    public FicheClient modifier(Long clientId, String telephone, String langue) {
        Client client = charger(clientId);
        Utilisateur u = client.getUtilisateur();

        if (telephone != null) {
            u.setTelephone(telephone.isBlank() ? null : telephone.strip());
        }
        if (langue != null && !langue.isBlank()) {
            u.setLangue(langue.strip());
        }

        return FicheClient.de(client);
    }

    private Client charger(Long clientId) {
        return clients.chargerAvecUtilisateur(clientId)
                .orElseThrow(() -> RessourceIntrouvable.de("Client", clientId));
    }
}
