package com.garah.api.logistique.domaine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * L'envoi d'une commande, vu par le client qui l'attend.
 *
 * <h2>Ce n'est pas {@link VueRetrait}, et c'est voulu</h2>
 *
 * <p>{@code VueRetrait} porte {@code expeditionId} et {@code clientId} : des
 * identifiants internes, utiles au back-office et à personne d'autre. Les
 * servir au client lui apprendrait le volume d'affaires de la plateforme par
 * simple lecture des numéros — et un identifiant affiché finit toujours par
 * être recopié dans un formulaire ou un message.</p>
 *
 * <p>Ce que le client a besoin de savoir tient en peu de choses : sous quel
 * numéro son envoi est parti, quels colis le composent — donc où ils en sont
 * —, avec quel code le retirer, et si c'est déjà fait.</p>
 *
 * <h2>🎯 Il existe dès que l'envoi existe, pas seulement au comptoir</h2>
 *
 * <p>Cette vue était bâtie sur le <b>retrait</b>, préparé par un agent quand
 * la marchandise arrive. Tant que rien n'était préparé, le client ne recevait
 * rien du tout : ni numéro d'envoi, ni suivi.</p>
 *
 * <p>⚠️ C'est-à-dire que le suivi n'apparaissait qu'une fois le colis
 * <b>arrivé</b> — précisément quand on n'a plus besoin de suivre. La question
 * « où est mon colis » se pose pendant le trajet, et c'est pendant le trajet
 * que l'écran restait muet.</p>
 *
 * <p>Un envoi sans retrait préparé rend donc une ligne dont {@code statut} et
 * {@code codeRetrait} sont nuls. Ce n'est pas un trou : c'est « parti, pas
 * encore arrivé ».</p>
 *
 * <h2>⚠️ Le code n'est pas toujours là — et son absence est une information</h2>
 *
 * <p>Il est nul tant que la marchandise n'est pas arrivée, et nul de nouveau
 * une fois la remise confirmée. Un code affiché après coup ferait revenir un
 * client au comptoir pour un colis qu'il a déjà emporté ; le laisser traîner
 * dans un écran rouvert des semaines plus tard est un secret partagé qui ne
 * protège plus rien.</p>
 *
 * <p>C'est {@code statut} qui dit laquelle des trois absences on regarde —
 * nul (rien n'est préparé), en attente, ou confirmé. L'écran ne doit jamais
 * déduire « pas encore prêt » d'un code manquant.</p>
 */
public record MonRetrait(
        String numeroExpedition,

        /**
         * Les numéros de suivi des colis de cette expédition.
         *
         * <h2>🎯 Le client ne les avait NULLE PART</h2>
         *
         * <p>La boutique propose « Suivre un colis », une page publique qui
         * demande un numéro de suivi. Ce numéro appartient au <b>colis</b>.
         * Or le client ne recevait que le numéro d'<b>expédition</b> — un
         * autre identifiant, que la page de suivi ne connaît pas.</p>
         *
         * <p>⚠️ Conséquence : la boutique annonçait un suivi que ses propres
         * clients ne pouvaient pas utiliser pour leur commande. Il fallait que
         * quelqu'un leur communique le numéro à la main.</p>
         *
         * <p>Une expédition peut porter <b>plusieurs</b> colis : c'est une
         * liste, pas un numéro. Un seul affiché ferait chercher les autres.</p>
         */
        List<String> numerosSuivi,

        String codeRetrait,
        String statut,
        Instant dateRetrait) {

    /**
     * @param r le retrait préparé, ou {@code null} tant qu'aucun ne l'est.
     */
    public static MonRetrait de(Expedition e, RetraitMarchandise r) {
        return new MonRetrait(
                e.getNumero(),
                e.getColis().stream()
                        .map(Colis::getNumeroSuivi)
                        .filter(Objects::nonNull)
                        .toList(),
                r == null || r.estConfirme() ? null : r.getCodeRetrait(),
                r == null ? null : r.getStatut(),
                r == null ? null : r.getDateRetrait());
    }
}
