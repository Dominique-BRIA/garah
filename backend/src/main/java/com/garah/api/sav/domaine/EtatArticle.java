package com.garah.api.sav.domaine;

/**
 * L etat d un article retourne — il decide du mouvement de stock.
 *
 * <pre>
 * NEUF          redevient vendable        → compteur DISPONIBLE
 * ABIME         invendable                → compteur ENDOMMAGEE
 * INUTILISABLE  invendable                → compteur ENDOMMAGEE
 * </pre>
 *
 * <p>Sans cette distinction, on revendrait une marchandise abimee. La §13 de
 * la specification le demandait explicitement.</p>
 */
public enum EtatArticle {
    NEUF,
    ABIME,
    INUTILISABLE;

    public boolean estRevendable() {
        return this == NEUF;
    }
}
