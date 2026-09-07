package com.garah.api.catalogue.domaine;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Compose le SKU et l'intitulé d'une déclinaison à partir de ses valeurs.
 *
 * <pre>
 * référence CH-2026 · Taille 42 · Couleur Blanc
 *     SKU       CH-2026-42-BLANC
 *     intitulé  42 — Blanc
 * </pre>
 *
 * <h2>Pourquoi les engendrer plutôt que les demander</h2>
 *
 * <p>Saisis à la main, ils divergent. C'est arrivé sur ce projet : une
 * déclinaison portait l'intitulé « 42 » et la référence « Taille » — les deux
 * champs inversés. Une autre, « Blanc » et « BL460 » — une référence inventée
 * qui ne se rattache à rien.</p>
 *
 * <p>Engendrés, les deux disent la <b>même chose</b> que les attributs, et le
 * jour où quelqu'un renomme une valeur, rien ne se contredit : le SKU reste
 * (il identifie), l'intitulé se recalcule (il décrit).</p>
 */
public final class CompositionVariante {

    /** Au-delà, la colonne SKU (60) déborde une fois la référence en tête. */
    private static final int MAX_SEGMENT = 12;

    private CompositionVariante() {
    }

    /**
     * Le SKU : la référence du produit, puis une abréviation par valeur.
     *
     * <p>⚠️ L'ordre des valeurs <b>compte</b>. Deux déclinaisons portant les
     * mêmes valeurs dans un ordre différent donneraient deux SKU différents
     * pour le même article. L'appelant les trie par attribut avant d'appeler.</p>
     */
    public static String sku(String referenceProduit, List<ValeurAttribut> valeurs) {
        if (valeurs.isEmpty()) {
            return referenceProduit;
        }

        String suffixe = valeurs.stream()
                .map(v -> abreger(v.getCode()))
                .collect(Collectors.joining("-"));

        return referenceProduit + "-" + suffixe;
    }

    /**
     * L'intitulé lisible : « 42 — Blanc ».
     *
     * <p>Les libellés, pas les codes : c'est ce qu'on lit sur un bordereau et
     * dans une conversation avec un client.</p>
     */
    public static String libelle(String nomProduit, List<ValeurAttribut> valeurs) {
        if (valeurs.isEmpty()) {
            return nomProduit;
        }

        return valeurs.stream()
                .map(ValeurAttribut::getLibelle)
                .collect(Collectors.joining(" — "));
    }

    /**
     * Un segment de SKU : majuscules, sans accent, sans séparateur.
     *
     * <p>Les tirets internes sont retirés parce qu'ils séparent déjà les
     * segments entre eux. En garder rendrait le SKU impossible à redécouper.</p>
     */
    private static String abreger(String code) {
        String sansAccents = Normalizer.normalize(code, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        String propre = sansAccents.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");

        return propre.length() <= MAX_SEGMENT ? propre : propre.substring(0, MAX_SEGMENT);
    }
}
