package com.garah.api.marchand.domaine;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Une règle de commission telle qu'un écran l'affiche.
 *
 * <h2>Les portées sont NOMMÉES, pas numérotées</h2>
 *
 * <p>Une règle porte deux identifiants facultatifs. Affichés bruts, ils
 * donneraient « marchand 7, catégorie 12 » — illisible dans un tableau qui
 * sert justement à répondre à « qui paie combien ? ».</p>
 *
 * @param marchandNom  {@code null} quand la règle vaut pour <b>tous</b> les
 *                     marchands. C'est une valeur porteuse de sens, pas une
 *                     donnée manquante : l'écran doit écrire « tous », jamais
 *                     un tiret.
 * @param categorieNom même chose pour la catégorie.
 * @param active       vrai si la règle s'applique <b>aujourd'hui</b>. Une
 *                     règle future ou expirée reste dans la liste — la
 *                     masquer ferait chercher pourquoi un taux paramétré ne
 *                     s'applique pas.
 */
public record VueRegleCommission(
        Long id,
        Long marchandId,
        String marchandNom,
        Long categorieProduitId,
        String categorieNom,
        BigDecimal taux,
        int priorite,
        LocalDate dateDebut,
        LocalDate dateFin,
        boolean active) {

    public static VueRegleCommission de(RegleCommission r, String marchandNom,
                                        String categorieNom, LocalDate jour) {
        boolean active = !r.getDateDebut().isAfter(jour)
                && (r.getDateFin() == null || r.getDateFin().isAfter(jour));

        return new VueRegleCommission(r.getId(),
                r.getMarchandId(), marchandNom,
                r.getCategorieProduitId(), categorieNom,
                r.getTaux(), r.getPriorite(),
                r.getDateDebut(), r.getDateFin(), active);
    }
}
