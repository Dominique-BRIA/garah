package com.garah.api.serviceclient.domaine;

/**
 * Ce qui vient d arriver dans une conversation, dit au reste du systeme.
 *
 * <h2>🎯 Des EVENEMENTS, et non des appels directs aux notifications</h2>
 *
 * <p>Le service client n a pas a savoir que Firebase existe. Le jour ou la
 * passerelle tombe, une conversation doit quand meme s ouvrir et un message
 * doit quand meme s enregistrer.</p>
 *
 * <p>⚠️ C est aussi ce qui evite un CYCLE : `notification` ecoute
 *    `serviceclient`, et jamais l inverse. Un appel direct aurait fait
 *    dependre les deux modules l un de l autre, et le test d architecture
 *    aurait refuse la compilation.</p>
 */
public final class EvenementsConversation {

    private EvenementsConversation() {
    }

    /**
     * Un client vient d ouvrir une conversation — souvent pour negocier.
     *
     * <p>Elle n a encore AUCUN proprietaire : c est un signal d equipe,
     * adresse a quiconque peut la prendre en charge.</p>
     */
    public record ConversationOuverte(Long conversationId, Long clientId, String sujet) {
    }

    /**
     * Quelqu un a repondu dans une conversation.
     *
     * @param versLeClient vrai si c est un conseiller qui ecrit au client, faux
     *                     si c est le client qui ecrit a l equipe. Le sens
     *                     decide du destinataire, et il ne se devine pas au
     *                     dernier moment.
     * @param responsableId celui qui suit le dossier. Nul tant que personne ne
     *                      l a pris : le message part alors a l equipe.
     * @param extrait       les premiers mots, POUR LA NOTIFICATION. Une
     *                      banniere affiche deux lignes ; y mettre le message
     *                      entier ne ferait que gonfler l envoi.
     * @param message       le message ENTIER, pour la diffusion temps reel.
     *                      L ecran d en face l affiche tel quel, sans
     *                      redemander le fil — ce qui serait une requete de
     *                      plus a chaque phrase echangee.
     */
    public record MessageDansConversation(Long conversationId, Long clientId,
                                          Long responsableId, Long expediteurId,
                                          String extrait, boolean versLeClient,
                                          VueMessage message) {
    }

    /** Une proposition de prix a ete faite, ou acceptee. */
    public record PropositionDePrix(Long conversationId, Long clientId,
                                    Long responsableId, boolean versLeClient,
                                    boolean acceptee) {
    }
}
