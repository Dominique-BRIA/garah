package com.garah.api.commun.audit;

import com.garah.api.commun.securite.Acteur;

import java.util.Map;

/**
 * Un geste posé par un CLIENT, sur son propre parcours.
 *
 * <h2>🎯 Pourquoi un événement de plus</h2>
 *
 * <p>{@link ActionAuditee} répond à « qui, <b>chez nous</b>, a touché à cette
 * donnée ? ». {@code EcouteurAudit} écarte donc explicitement les acteurs
 * clients, et son commentaire dit depuis le début : « le parcours d'un client
 * est une autre question, qui a son propre journal ».</p>
 *
 * <p>Ce journal existe en base depuis V12 — {@code activite_client}, avec ses
 * huit types et ses deux index. Il n'avait <b>ni entité, ni écriture</b> :
 * une table déclarée que personne ne remplit, exactement le défaut trouvé
 * ailleurs cette semaine. Cet événement est ce qui la remplit.</p>
 *
 * <h2>⚠️ Pourquoi un événement plutôt qu'un appel direct</h2>
 *
 * <p>Les gestes viennent de partout — {@code commerce}, {@code panier},
 * {@code sav}, {@code iam}. Si chacun appelait la surveillance, quatre
 * domaines en dépendraient, et le test d'architecture refuserait le cycle qui
 * en naîtrait tôt ou tard. Un événement publié dans {@code commun} ne crée
 * aucune dépendance : la surveillance écoute, personne ne l'appelle.</p>
 *
 * @param acteur   celui qui agit ; les gestes d'un acteur INTERNE sont ignorés
 * @param type     l'un des huit types acceptés par {@code activite_client}
 * @param contexte ce qui rend la ligne lisible six mois plus tard — le numéro
 *                 d'une commande, le nom d'un article. ⚠️ Jamais de donnée
 *                 sensible : ce journal se lit depuis le back-office
 */
public record GesteClient(
        Acteur acteur,
        TypeGesteClient type,
        Map<String, String> contexte) {

    /**
     * ⚠️ Le contexte réutilise {@link JournalActions#cliche} : même façon
     *    d'écrire un cliché des deux côtés, donc une seule à apprendre. Il
     *    accepte les valeurs nulles — une commande sans numéro pendant sa
     *    création en est une.
     */
    public static GesteClient de(Acteur acteur, TypeGesteClient type,
                                 Object... champsEtValeurs) {
        return new GesteClient(acteur, type, JournalActions.cliche(champsEtValeurs));
    }

    /**
     * Les huit types, tels que la contrainte de V12 les accepte.
     *
     * <p>⚠️ Ce sont <b>exactement</b> ceux de
     * {@code activite_client_type_valide}. Un neuvième ajouté ici sans
     * migration ferait échouer l'insertion — et un journal qu'on ne peut pas
     * écrire ne se voit qu'au moment où on en a besoin.</p>
     */
    public enum TypeGesteClient {
        /**
         * ⚠️ <b>Non utilisés, et volontairement.</b> Les faits
         * d'authentification ont leur propre journal —
         * {@code evenement_securite} — qui existe pour répondre à « y a-t-il
         * une anomalie ? ». Les recopier ici doublerait chaque session sans
         * rien apprendre sur le parcours d'achat.
         *
         * <p>Ils restent déclarés parce que la contrainte de V12 les accepte :
         * le jour où l'on voudra reconstituer une session complète côté
         * client, la place est faite.</p>
         */
        CONNEXION,
        DECONNEXION,
        /**
         * ⚠️ <b>Non utilisé, et volontairement.</b> Les vues de produits ont
         * déjà leur table — {@code vue_produit} — écrite par
         * {@code ServiceStatistiques}, avec ses agrégats journaliers. Les
         * écrire ici aussi doublerait chaque consultation : le journal du
         * parcours deviendrait illisible sous le trafic de la vitrine, ce qui
         * est précisément le défaut que la séparation des trois journaux
         * évitait.
         */
        VUE_PRODUIT,
        AJOUT_PANIER,
        COMMANDE,
        ANNULATION,
        PAIEMENT,
        RECLAMATION
    }
}
