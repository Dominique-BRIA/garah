package com.garah.api.logistique.domaine;

import java.time.Instant;

/**
 * Le retrait, vu par le client qui l'attend.
 *
 * <h2>Ce n'est pas {@link VueRetrait}, et c'est voulu</h2>
 *
 * <p>{@code VueRetrait} porte {@code expeditionId} et {@code clientId} : des
 * identifiants internes, utiles au back-office et à personne d'autre. Les
 * servir au client lui apprendrait le volume d'affaires de la plateforme par
 * simple lecture des numéros — et un identifiant affiché finit toujours par
 * être recopié dans un formulaire ou un message.</p>
 *
 * <p>Ce que le client a besoin de savoir tient en quatre choses : y a-t-il
 * quelque chose à retirer, sous quel numéro d'envoi, avec quel code, et si
 * c'est déjà fait.</p>
 *
 * <h2>⚠️ Le code n'est pas toujours là — et son absence est une information</h2>
 *
 * <p>Il est nul tant que la marchandise n'est pas arrivée, et nul de nouveau
 * une fois la remise confirmée. Un code affiché après coup ferait revenir un
 * client au comptoir pour un colis qu'il a déjà emporté ; le laisser traîner
 * dans un écran rouvert des semaines plus tard est un secret partagé qui ne
 * protège plus rien.</p>
 *
 * <p>C'est {@code statut} qui dit laquelle des deux absences on regarde :
 * l'écran ne doit jamais déduire « pas encore prêt » d'un code manquant.</p>
 */
public record MonRetrait(
        String numeroExpedition,
        String codeRetrait,
        String statut,
        Instant dateRetrait) {

    public static MonRetrait de(RetraitMarchandise r, Expedition e) {
        return new MonRetrait(
                e.getNumero(),
                r.estConfirme() ? null : r.getCodeRetrait(),
                r.getStatut(),
                r.getDateRetrait());
    }
}
