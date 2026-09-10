package com.garah.api.messagerie.domaine;

/**
 * Quelqu'un à qui l'on peut écrire.
 *
 * <p>⚠️ Le champ s'appelait {@code responsableId}, et ce nom mentait depuis
 * V32 : la liste contient <b>tout compte interne</b>, administration comprise.
 * Un nom qui désigne un type d'acteur alors qu'il en accepte quatre est
 * exactement ce qui avait fait passer une contrainte de schéma pour une règle
 * métier.</p>
 *
 * <p>⚠️ C'est un <b>contrat</b> : l'écran des messages le lit. Serveur et
 * back-office doivent donc être déployés ensemble — un décalage laisserait la
 * liste des destinataires vide, sans erreur.</p>
 */
public record VueCollegue(Long utilisateurId, String nom) {
}
