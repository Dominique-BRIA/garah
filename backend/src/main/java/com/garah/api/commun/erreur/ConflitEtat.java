package com.garah.api.commun.erreur;

import org.springframework.http.HttpStatus;

/**
 * L'objet n'est pas dans l'état attendu, ou quelqu'un a été plus rapide.
 * Répond en {@code 409 Conflict}.
 *
 * <p>C'est l'exception des <b>machines à états</b> (chapitre 04) et de la
 * <b>concurrence</b> (chapitre 05) :</p>
 * <ul>
 *   <li>« Cette conversation a déjà été prise par un autre responsable. »</li>
 *   <li>« Cette commande est déjà expédiée, elle ne peut plus être annulée. »</li>
 *   <li>« Il ne reste que 2 unités en stock. »</li>
 * </ul>
 *
 * <p>Ce n'est <b>pas</b> une erreur de l'utilisateur : c'est le monde qui a
 * changé entre le moment où il a affiché la page et celui où il a cliqué.
 * Le message doit le dire ainsi, jamais l'accuser.</p>
 */
public class ConflitEtat extends ErreurMetier {

    public ConflitEtat(String code, String message) {
        super(code, message);
    }

    /**
     * Transition d'état refusée.
     *
     * <p>Le message nomme les deux états : sans ça, l'utilisateur ne peut pas
     * comprendre ce qu'il doit faire.</p>
     */
    public static ConflitEtat transitionInterdite(String entite, String depuis, String vers) {
        return new ConflitEtat(
                "TRANSITION_INTERDITE",
                "Impossible de passer " + entite + " de l'état " + depuis + " à l'état " + vers + ".");
    }

    @Override
    public HttpStatus getStatut() {
        return HttpStatus.CONFLICT;
    }
}
