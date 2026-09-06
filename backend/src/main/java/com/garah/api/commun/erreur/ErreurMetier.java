package com.garah.api.commun.erreur;

import org.springframework.http.HttpStatus;

/**
 * Racine de toutes les erreurs métier de GARAH.
 *
 * <p>Une erreur métier porte deux choses distinctes, et c'est volontaire :</p>
 * <ul>
 *   <li>un <b>code</b> stable et technique ({@code STOCK_INSUFFISANT}), que le
 *       frontend teste et qui sert de clé de traduction ;</li>
 *   <li>un <b>message</b> humain, qui peut changer sans rien casser.</li>
 * </ul>
 *
 * <p>C'est le même principe que {@code cas_utilisation.code} : un code pour la
 * machine, un libellé pour l'humain.</p>
 */
public abstract class ErreurMetier extends RuntimeException {

    private final String code;

    protected ErreurMetier(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** Le statut HTTP que cette erreur doit produire. */
    public abstract HttpStatus getStatut();
}
