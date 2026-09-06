package com.garah.api.commun.stockage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reconstruit l'URL publique d'un fichier à partir de sa clé d'objet.
 *
 * <p>La base ne stocke <b>jamais</b> une URL complète (D-14) :</p>
 *
 * <pre>
 * en base      produits/42/photo-1.jpg
 * affiché      https://f003.backblazeb2.com/file/garah-medias/produits/42/photo-1.jpg
 * </pre>
 *
 * <p><b>Pourquoi cette classe existe.</b> Si on stockait l'URL complète, le
 * jour où GARAH quitte Backblaze B2 pour un MinIO sur le VPS, il faudrait
 * réécrire toutes les lignes de {@code media} — et, tant que la migration
 * n'est pas terminée, la moitié du catalogue pointerait vers un hébergeur
 * qu'on ne paie plus. Avec une clé, on change <b>une variable
 * d'environnement</b> et on redémarre.</p>
 *
 * <p>C'est la même idée que la règle de la photographie du chapitre 03, prise
 * à l'envers : on copie ce qui a une valeur <b>juridique</b> (un prix, un
 * nom), on référence ce qui n'est qu'une <b>adresse technique</b>.</p>
 */
@Component
public class StockageObjet {

    /**
     * Le préfixe public, sans barre oblique finale.
     *
     * <p>La valeur par défaut vise un stockage local : elle permet de démarrer
     * l'application sans compte Backblaze, ce qui compte pour quelqu'un qui
     * clone le dépôt pour la première fois.</p>
     */
    private final String baseUrl;

    public StockageObjet(@Value("${GARAH_MEDIA_BASE_URL:http://localhost:9000/garah-medias}")
                         String baseUrl) {
        this.baseUrl = sansBarreFinale(baseUrl);
    }

    /**
     * Transforme une clé d'objet en URL publique.
     *
     * <p>Trois comportements, et chacun corrige une erreur qu'on fait
     * réellement :</p>
     *
     * <ul>
     *   <li>une clé {@code null} ou vide renvoie {@code null} — un produit sans
     *       photo est un cas normal, pas une exception ;</li>
     *   <li>une clé qui commence par {@code /} est nettoyée — sinon on
     *       produirait {@code .../garah-medias//produits/42.jpg}, que certains
     *       serveurs S3 traitent comme un autre objet ;</li>
     *   <li>une clé qui est <b>déjà</b> une URL absolue est renvoyée telle
     *       quelle — c'est le cas des lignes importées d'un ancien système, et
     *       préfixer l'URL les casserait silencieusement.</li>
     * </ul>
     */
    public String urlPublique(String cleObjet) {
        if (cleObjet == null || cleObjet.isBlank()) {
            return null;
        }

        String cle = cleObjet.strip();
        if (estAbsolue(cle)) {
            return cle;
        }

        return baseUrl + "/" + cle.replaceFirst("^/+", "");
    }

    /** Le préfixe tel qu'il sera annoncé aux trois frontends. */
    public String baseUrl() {
        return baseUrl;
    }

    private static boolean estAbsolue(String valeur) {
        return valeur.startsWith("http://") || valeur.startsWith("https://");
    }

    private static String sansBarreFinale(String valeur) {
        return valeur == null ? "" : valeur.replaceFirst("/+$", "");
    }
}
