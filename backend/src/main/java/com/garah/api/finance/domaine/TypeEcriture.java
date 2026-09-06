package com.garah.api.finance.domaine;

import java.math.BigDecimal;

/**
 * Les natures d ecriture du grand livre marchand.
 *
 * <p>Le SIGNE est impose par le type, et la base le verifie
 * ({@code ecriture_marchand_signe_coherent}). Une VENTE ne peut pas etre
 * negative, une COMMISSION ne peut pas etre positive.</p>
 *
 * <p>Pourquoi c est important : une inversion de signe fausserait TOUS les
 * soldes sans jamais lever d erreur. Le solde etant une somme, une seule
 * ligne mal signee suffit a rendre la dette fausse — et rien ne le signale.</p>
 */
public enum TypeEcriture {

    /** Ce qu on doit au marchand pour une vente. Positif. */
    VENTE(1),

    /** Ce qu on preleve au passage. Negatif. */
    COMMISSION(-1),

    /** Annulation d une vente apres retour. Negatif. */
    RETOUR(-1),

    /** On rend la commission prelevee sur ce qui a ete retourne. Positif. */
    ANNUL_COMMISSION(1),

    /** Versement effectue au marchand : la dette diminue. Negatif. */
    REGLEMENT(-1),

    /** La seule soupape : correction manuelle, signe libre. */
    AJUSTEMENT(0);

    private final int signe;

    TypeEcriture(int signe) {
        this.signe = signe;
    }

    /** Oriente un montant positif selon le type. AJUSTEMENT est laisse tel quel. */
    public BigDecimal orienter(BigDecimal montantPositif) {
        return signe < 0 ? montantPositif.negate() : montantPositif;
    }

    public boolean signeLibre() {
        return signe == 0;
    }
}
