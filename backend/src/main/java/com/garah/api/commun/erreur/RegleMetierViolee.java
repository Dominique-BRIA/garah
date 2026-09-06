package com.garah.api.commun.erreur;

import org.springframework.http.HttpStatus;

/**
 * La demande est bien formée, mais elle viole une règle métier.
 * Répond en {@code 422 Unprocessable Entity}.
 *
 * <p>Exemples GARAH :</p>
 * <ul>
 *   <li>publier un produit qui n'a aucune variante avec un prix (I-12) ;</li>
 *   <li>accepter une proposition de prix expirée (I-33) ;</li>
 *   <li>rembourser plus que ce que le client a payé (I-27).</li>
 * </ul>
 *
 * <p>On distingue ce cas d'un {@code 400} : un {@code 400} signifie « ta
 * requête est mal formée », un {@code 422} signifie « ta requête est correcte,
 * mais je ne peux pas la satisfaire ». Le frontend ne réagit pas pareil.</p>
 */
public class RegleMetierViolee extends ErreurMetier {

    public RegleMetierViolee(String code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus getStatut() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
