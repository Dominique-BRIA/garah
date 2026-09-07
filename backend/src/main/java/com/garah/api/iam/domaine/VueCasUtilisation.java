package com.garah.api.iam.domaine;

/**
 * Une fonctionnalite attribuable, telle que l'ecran de profil la propose.
 *
 * <p>Le {@code code} est ce que le code Java verifie ({@code @PreAuthorize})
 * et ce que le jeton transporte. Le {@code nom} est ce qu'on lit a l'ecran.
 * Les deux sont exposes : sans le nom, choisir une permission reviendrait a
 * cocher des constantes en majuscules ; sans le code, on ne pourrait plus
 * rapprocher un droit d'une erreur d'acces.</p>
 *
 * <p>Le {@code module} sert a grouper. Cent quatre-vingt-huit cases a cocher
 * sur une seule liste ne se lisent pas.</p>
 */
public record VueCasUtilisation(String code, String nom, String description, String module) {

    public static VueCasUtilisation de(CasUtilisation cas) {
        return new VueCasUtilisation(cas.getCode(), cas.getNom(),
                cas.getDescription(), cas.getModule());
    }
}
