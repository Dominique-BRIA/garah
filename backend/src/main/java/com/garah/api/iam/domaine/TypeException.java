package com.garah.api.iam.domaine;

/**
 * Sens d'une exception individuelle de permission (D-02).
 *
 * <p>⚠️ Le {@link #REMOVE} s'applique <b>après</b> l'union de toutes les
 * catégories du responsable, jamais catégorie par catégorie. Sinon un droit
 * retiré d'un profil serait redonné par un autre.</p>
 */
public enum TypeException {
    /** Ajoute une permission que la catégorie ne donne pas. */
    ADD,
    /** Retire une permission, même si plusieurs catégories la donnent. */
    REMOVE
}
