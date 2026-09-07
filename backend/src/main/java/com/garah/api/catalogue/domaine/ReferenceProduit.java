package com.garah.api.catalogue.domaine;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Fabrique la référence d'un produit à partir de ce qui l'identifie déjà.
 *
 * <pre>
 * marchand 202020 · catégorie « Chaussure » · produit « Adidas »
 *                        ──▶  202020-CHA-ADIDAS
 * </pre>
 *
 * <p><b>Pourquoi l'engendrer plutôt que la demander.</b> Saisie à la main, la
 * référence devient ce que la personne a sous les yeux ce jour-là : « AD20 »,
 * « 202020 », « test2 ». Trois mois plus tard, personne ne sait plus de quel
 * produit il s'agit, et deux marchands différents ont fini par choisir le même
 * code. Engendrée, elle porte l'information qu'on cherchera quand on la lira
 * sur un bordereau : de qui, de quelle famille, de quel article.</p>
 *
 * <p><b>Ce que cette classe ne fait PAS : garantir l'unicité.</b> Deux produits
 * « Adidas » du même marchand dans la même catégorie produisent la même base.
 * L'unicité se règle contre la base de données, dans {@link ServiceCatalogue} —
 * seul endroit qui puisse savoir ce qui existe déjà.</p>
 */
public final class ReferenceProduit {

    /*
     * La colonne accepte 50 caractères. On tient dans 41 au pire, ce qui laisse
     * de quoi suffixer « -999 » sans dépasser :
     *   12 (marchand) + 1 + 3 (catégorie) + 1 + 24 (produit) = 41
     */
    private static final int MAX_MARCHAND = 12;
    private static final int MAX_CATEGORIE = 3;
    private static final int MAX_NOM = 24;

    private ReferenceProduit() {
    }

    public static String de(String codeMarchand, String nomCategorie, String nomProduit) {
        return compact(codeMarchand, MAX_MARCHAND, "MAR")
                + "-" + compact(nomCategorie, MAX_CATEGORIE, "GEN")
                + "-" + lisible(nomProduit, MAX_NOM, "PRODUIT");
    }

    /**
     * Un segment ramassé, sans séparateur : « MAR-00042 » devient « MAR00042 »,
     * « T-shirts » devient « TSH ».
     *
     * <p>Les tirets sont retirés parce qu'ils servent déjà à séparer les trois
     * segments entre eux. En garder à l'intérieur rendrait la référence
     * impossible à redécouper — et « T-S » abrégerait mal « T-shirts ».</p>
     */
    private static String compact(String texte, int max, String repli) {
        String propre = sansAccents(texte).replaceAll("[^A-Z0-9]", "");
        return propre.isEmpty() ? repli : tronquer(propre, max);
    }

    /**
     * Un segment qui garde ses mots séparés : « Chemise Oxford » devient
     * « CHEMISE-OXFORD ».
     *
     * <p>C'est le nom du produit, celui qu'on lit ; le coller d'un seul tenant
     * le rendrait illisible sur un bordereau.</p>
     */
    private static String lisible(String texte, int max, String repli) {
        String propre = sansAccents(texte)
                .replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (propre.isEmpty()) {
            return repli;
        }

        // Le tiret qui traîne après la coupe est retiré : « CHEMISE-OXF- »
        // n'est pas une référence, c'est une phrase interrompue.
        String coupe = tronquer(propre, max).replaceAll("-+$", "");
        return coupe.isEmpty() ? repli : coupe;
    }

    private static String sansAccents(String texte) {
        if (texte == null) {
            return "";
        }
        // NFD sépare les lettres de leurs accents, qu'on retire ensuite :
        // « é » devient « e » plutôt que d'être supprimé.
        return Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String tronquer(String texte, int max) {
        return texte.length() <= max ? texte : texte.substring(0, max);
    }
}
