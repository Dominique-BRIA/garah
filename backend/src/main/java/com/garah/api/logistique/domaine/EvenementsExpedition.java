package com.garah.api.logistique.domaine;

/**
 * Ce qui vient d'arriver à une marchandise, dit au reste du système.
 *
 * <h2>🎯 Le client n'était prévenu de RIEN</h2>
 *
 * <p>Toute la logistique était muette vers l'extérieur. Un colis partait, un
 * retrait était préparé, et le client ne l'apprenait qu'en rouvrant l'écran de
 * sa commande — c'est-à-dire s'il pensait à le faire.</p>
 *
 * <p>⚠️ La méthode {@code marchandiseArrivee} existait pourtant côté
 * notifications, écrite et documentée. <b>Personne ne l'appelait.</b> Une
 * notification qu'aucun code ne déclenche ne se distingue en rien d'une
 * notification qui n'existe pas, et rien ne le signalait.</p>
 *
 * <h2>Des ÉVÉNEMENTS, et non un appel direct</h2>
 *
 * <p>La logistique n'a pas à savoir que Firebase existe : le jour où la
 * passerelle tombe, un colis doit quand même pouvoir partir. C'est aussi ce
 * qui évite un cycle — {@code notification} écoute {@code logistique}, jamais
 * l'inverse, et le test d'architecture refuserait la compilation autrement.</p>
 */
public final class EvenementsExpedition {

    private EvenementsExpedition() {
    }

    /**
     * Un colis vient de quitter son point de départ.
     *
     * <p>C'est le moment où le numéro de suivi devient utile : à partir de
     * maintenant, il y a quelque chose à suivre. L'annoncer plus tard — à
     * l'arrivée — reviendrait à donner le suivi quand il ne sert plus.</p>
     *
     * @param numeroSuivi le numéro que le guichet public sait lire. Il est
     *                    dans le message : contrairement au code de retrait,
     *                    il ne permet à personne d'emporter la marchandise.
     */
    public record ColisParti(Long clientId, Long commandeId, String numeroSuivi) {
    }

    /**
     * La marchandise attend le client au comptoir.
     *
     * <p>⚠️ Le code de retrait n'est <b>pas</b> dans cet événement, et ne doit
     * jamais l'être : une bannière s'affiche sur un écran verrouillé, à la vue
     * de qui passe. On dit que c'est arrivé, pas comment le prendre.</p>
     */
    public record MarchandiseDisponible(Long clientId, Long commandeId, String pointRecuperation) {
    }
}
