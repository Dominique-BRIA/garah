package com.garah.api.catalogue.domaine;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Fabrique un identifiant lisible pour les URL.
 *
 * <pre>
 * « Chemise Oxford — Bleu ciel »  ──▶  chemise-oxford-bleu-ciel
 * </pre>
 *
 * <p><b>Pourquoi un slug plutôt que l'identifiant numérique dans l'URL :</b></p>
 * <ul>
 *   <li>{@code /produits/chemise-oxford} se lit, se partage et se référence ;</li>
 *   <li>{@code /produits/42} révèle combien de produits existent — un
 *       concurrent en déduit le volume d'activité en trois requêtes.</li>
 * </ul>
 *
 * <p>Le slug est <b>figé à la création</b>. Renommer un produit ne le change
 * pas : sinon tous les liens partagés et l'indexation Google seraient cassés.</p>
 */
public final class Slug {

    private Slug() {
    }

    public static String de(String texte) {
        if (texte == null || texte.isBlank()) {
            throw new IllegalArgumentException("Impossible de fabriquer un slug depuis un texte vide.");
        }

        // NFD sépare les lettres de leurs accents, qu'on retire ensuite :
        // « é » devient « e » plutôt que d'être supprimé.
        String sansAccents = Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return sansAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")   // tout le reste devient un tiret
                .replaceAll("^-+|-+$", "");      // pas de tiret au début ni à la fin
    }
}
