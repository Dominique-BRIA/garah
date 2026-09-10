package com.garah.api.logistique.domaine;

/**
 * Où en est, physiquement, la marchandise d'une commande.
 *
 * <p>C'est une <b>lecture</b> des faits logistiques — expéditions, colis,
 * événements —, jamais une saisie. La commande en déduit son statut.</p>
 *
 * <h2>⚠️ Au rythme du MOINS avancé</h2>
 *
 * <p>Même règle que l'expédition envers ses colis : une commande n'est
 * « en route » que lorsque TOUT est parti, « à retirer » que lorsque TOUT
 * est arrivé. L'annoncer disponible alors qu'un colis roule encore ferait
 * venir le client pour la moitié de sa commande.</p>
 *
 * <p>Un colis VIDE — créé puis jamais rempli — n'est pas compté : il ne
 * partira jamais, et bloquerait la commande pour toujours.</p>
 */
public enum AvancementCommande {

    /** Aucune expédition : rien n'a commencé. */
    RIEN,

    /** Une expédition existe, mais tout n'est pas encore mis en colis. */
    EN_PREPARATION,

    /** Tout est en colis, et au moins un colis n'est pas encore parti. */
    PRETE,

    /** Tout est parti, et au moins un colis n'est pas encore arrivé. */
    EXPEDIEE,

    /** Tout est au comptoir, et au moins un colis n'a pas été remis. */
    DISPONIBLE,

    /** Tout a été remis au client. */
    REMISE
}
