package com.garah.api.iam.domaine;

/**
 * La place de quelqu'un dans la hiérarchie.
 *
 * <pre>
 * 1  SUPER_ADMIN       agit SUR le SI
 * 2  ADMIN             agit DANS le SI
 * 3  CHEF_DE_SERVICE   dirige au moins un service
 * 4  MEMBRE            exécute le travail
 * </pre>
 *
 * <h2>🎯 Le rang se DÉDUIT, il ne se stocke pas</h2>
 *
 * <p>Les deux premiers viennent de {@link TypeUtilisateur}, qui existe depuis
 * le début ; le troisième vient du drapeau {@code chef} d'une affectation. Une
 * colonne {@code rang} en base aurait été une <b>troisième</b> source de
 * vérité, à tenir d'accord avec les deux autres — et le jour où l'on démet un
 * chef sans mettre le rang à jour, il continue de commander.</p>
 *
 * <h2>⚠️ Le plus petit nombre est le plus haut placé</h2>
 *
 * <p>C'est l'usage militaire et administratif — « premier », « second » — et
 * il rend la comparaison lisible : {@code peutAgirSur} demande simplement que
 * l'acteur soit <b>strictement inférieur</b>.</p>
 */
public enum RangHierarchique {

    SUPER_ADMIN(1),
    ADMIN(2),
    CHEF_DE_SERVICE(3),
    MEMBRE(4);

    private final int niveau;

    RangHierarchique(int niveau) {
        this.niveau = niveau;
    }

    public int niveau() {
        return niveau;
    }

    /**
     * Peut-on agir sur quelqu'un de ce rang ?
     *
     * <h2>⚠️ STRICTEMENT supérieur, et pas « supérieur ou égal »</h2>
     *
     * <p>Deux chefs de même rang ne doivent pas pouvoir se désactiver l'un
     * l'autre : le premier à cliquer gagnerait, et le second se retrouverait
     * dehors sans recours. Un Admin ne suspend pas non plus un autre Admin —
     * c'est au Super Admin de trancher entre pairs.</p>
     *
     * <p>La même règle protège de l'auto-destruction : on ne peut pas agir sur
     * soi-même par cette voie, puisqu'on a son propre rang.</p>
     */
    public boolean peutAgirSur(RangHierarchique autre) {
        return this.niveau < autre.niveau;
    }

    /**
     * Le rang d'un type d'utilisateur, avant de savoir s'il dirige un service.
     *
     * <p>Un {@code CLIENT} n'a pas sa place dans cette hiérarchie : il n'agit
     * sur personne et personne n'agit sur lui par ce chemin. Le traiter comme
     * un membre serait une erreur silencieuse — d'où le refus explicite.</p>
     */
    public static RangHierarchique de(TypeUtilisateur type, boolean dirigeUnService) {
        return switch (type) {
            case SUPER_ADMIN -> SUPER_ADMIN;
            case ADMIN -> ADMIN;
            case RESPONSABLE -> dirigeUnService ? CHEF_DE_SERVICE : MEMBRE;
            case CLIENT -> throw new IllegalArgumentException(
                    "Un client n'a pas de rang hiérarchique : "
                    + "la hiérarchie ne décrit que l'équipe.");
        };
    }
}
