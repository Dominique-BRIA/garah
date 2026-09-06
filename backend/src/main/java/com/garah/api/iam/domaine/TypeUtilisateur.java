package com.garah.api.iam.domaine;

/**
 * Les quatre acteurs du SI (chapitre 01 §3).
 *
 * <p>La ligne de partage entre SuperAdmin et Admin :</p>
 * <pre>
 * SUPER_ADMIN  agit SUR le SI     (quelles fonctionnalités existent)
 * ADMIN        agit DANS le SI    (qui les utilise, et sur quoi)
 * RESPONSABLE  exécute le travail
 * CLIENT       consomme le service
 * </pre>
 *
 * <p>Les valeurs correspondent exactement à la contrainte
 * {@code utilisateur_type_valide} du schéma. Ajouter une valeur ici sans
 * migration ferait échouer l'insertion — et c'est très bien ainsi.</p>
 */
public enum TypeUtilisateur {
    SUPER_ADMIN,
    ADMIN,
    RESPONSABLE,
    CLIENT
}
