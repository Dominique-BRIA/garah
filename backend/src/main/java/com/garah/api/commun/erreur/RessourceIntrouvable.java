package com.garah.api.commun.erreur;

import org.springframework.http.HttpStatus;

/**
 * L'objet demandé n'existe pas. Répond en {@code 404}.
 *
 * <p>À ne pas confondre avec {@link ConflitEtat} : ici l'objet n'existe pas du
 * tout, là il existe mais n'est pas dans l'état attendu.</p>
 */
public class RessourceIntrouvable extends ErreurMetier {

    public RessourceIntrouvable(String message) {
        super("RESSOURCE_INTROUVABLE", message);
    }

    /**
     * Raccourci pour le cas le plus fréquent.
     *
     * <p>Attention : le message est renvoyé au client. On y met le type et
     * l'identifiant, jamais une information interne.</p>
     */
    public static RessourceIntrouvable de(String type, Object identifiant) {
        return new RessourceIntrouvable(type + " introuvable : " + identifiant);
    }

    @Override
    public HttpStatus getStatut() {
        return HttpStatus.NOT_FOUND;
    }
}
