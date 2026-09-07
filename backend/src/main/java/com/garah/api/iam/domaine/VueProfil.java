package com.garah.api.iam.domaine;

import java.util.List;

/**
 * Un profil métier — ce que le schéma appelle {@code categorie_responsable}.
 *
 * <p><b>Pourquoi « profil » et non « catégorie » à l'écran.</b> Le mot
 * « catégorie » désigne déjà les familles de produits du catalogue. Employé
 * pour deux choses sans rapport, il oblige à demander « catégorie de quoi ? »
 * à chaque fois. La base garde son nom, l'interface en emploie un autre : la
 * traduction se fait ici, une fois.</p>
 *
 * <p>Le nom du profil sert aussi de <b>titre affiché</b> du responsable. Il
 * n'existe volontairement aucun champ « titre » : il finirait par diverger.</p>
 *
 * @param principale renseigné uniquement quand le profil est vu depuis un
 *                   responsable — c'est celui qui donne son titre
 * @param permissions vide dans les listes, remplie sur le détail
 */
public record VueProfil(
        Long id,
        String nom,
        String description,
        String statut,
        Boolean principale,
        int nombreMembres,
        List<String> permissions) {

    /** La forme courte, telle qu'elle apparaît sur la fiche d'un membre. */
    public static VueProfil resume(CategorieResponsable c, boolean principale) {
        return new VueProfil(c.getId(), c.getNom(), c.getDescription(), c.getStatut(),
                principale, 0, List.of());
    }

    /** La forme complète, avec les permissions que ce profil accorde. */
    public static VueProfil complet(CategorieResponsable c, int nombreMembres) {
        List<String> codes = c.getCasUtilisation().stream()
                .map(CasUtilisation::getCode)
                .sorted()
                .toList();

        return new VueProfil(c.getId(), c.getNom(), c.getDescription(), c.getStatut(),
                null, nombreMembres, codes);
    }
}
