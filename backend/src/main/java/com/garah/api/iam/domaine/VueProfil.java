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
 * @param chef renseigné dans le même cas : ce responsable DIRIGE ce service.
 *             Le drapeau vit sur l'affectation, pas sur le profil — un profil
 *             n'est chef de rien, c'est une personne qui l'est
 * @param chefNom le nom de celui qui dirige, vu DEPUIS le profil. Nul quand
 *                le service n'a pas de chef : c'est l'état d'avant la
 *                nomination, pas une anomalie
 * @param permissions vide dans les listes, remplie sur le détail
 */
public record VueProfil(
        Long id,
        String nom,
        String description,
        String statut,
        Boolean principale,
        Boolean chef,
        Long chefId,
        String chefNom,
        int nombreMembres,
        List<String> permissions) {

    /** La forme courte, telle qu'elle apparaît sur la fiche d'un membre. */
    public static VueProfil resume(CategorieResponsable c, boolean principale, boolean chef) {
        return new VueProfil(c.getId(), c.getNom(), c.getDescription(), c.getStatut(),
                principale, chef, null, null, 0, List.of());
    }

    /**
     * La forme complète, avec les permissions que ce profil accorde et le
     * responsable qui le dirige.
     *
     * <p>{@code chefId} nul signifie « ce service n'a pas encore de chef » —
     * un état normal, celui d'avant la nomination.</p>
     */
    public static VueProfil complet(CategorieResponsable c, int nombreMembres,
                                    Long chefId, String chefNom) {
        List<String> codes = c.getCasUtilisation().stream()
                .map(CasUtilisation::getCode)
                .sorted()
                .toList();

        return new VueProfil(c.getId(), c.getNom(), c.getDescription(), c.getStatut(),
                null, null, chefId, chefNom, nombreMembres, codes);
    }
}
