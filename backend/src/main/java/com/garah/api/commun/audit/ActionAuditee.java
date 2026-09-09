package com.garah.api.commun.audit;

import com.garah.api.commun.securite.Acteur;

/**
 * Un geste interne vient d'être posé sur une donnée.
 *
 * <h2>🎯 Un événement, et non un appel</h2>
 *
 * <p>Le journal d'audit vit dans le module <b>surveillance</b>. Si le
 * catalogue, la finance, la logistique et l'IAM l'appelaient directement,
 * chacun dépendrait de la surveillance — et la surveillance, qui lit des
 * comptes, dépendrait d'eux en retour. Le test d'architecture refuserait le
 * cycle, et il aurait raison : plus rien ne se déplacerait sans tout
 * déplacer.</p>
 *
 * <p>L'événement inverse la dépendance. Les modules <b>annoncent</b> ; la
 * surveillance écoute. Personne n'a besoin de savoir qu'un journal existe.</p>
 *
 * <h2>⚠️ L'acteur est capturé À LA SOURCE</h2>
 *
 * <p>Il voyage dans l'événement au lieu d'être relu par l'écouteur. Relu plus
 * tard, il serait relu dans un contexte qui a pu changer — et le jour où
 * l'écoute deviendrait asynchrone, le contexte de sécurité serait déjà
 * vidé : toutes les lignes porteraient « Système ». Ce défaut-là ne se voit
 * pas, il se lit six mois plus tard dans un journal devenu inutile.</p>
 *
 * @param avant l'état d'avant, ou {@code null} pour une création
 * @param apres l'état d'après, ou {@code null} pour une suppression
 */
public record ActionAuditee(
        Acteur acteur,
        String action,
        String entite,
        Long entiteId,
        Object avant,
        Object apres) {
}
