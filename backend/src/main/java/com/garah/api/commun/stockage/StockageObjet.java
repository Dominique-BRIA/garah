package com.garah.api.commun.stockage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StockageObjet.class);

    /** Ce qu'on utilise quand rien n'est configuré : un stockage local. */
    private static final String DEFAUT = "http://localhost:9000/garah-medias";

    /**
     * Le préfixe public, sans barre oblique finale.
     *
     * <p>La valeur de repli vise un stockage local : elle permet de démarrer
     * l'application sans compte Backblaze, ce qui compte pour quelqu'un qui
     * clone le dépôt pour la première fois.</p>
     */
    private final String baseUrl;

    /**
     * Signe les URL quand le bucket est privé ; inactif sinon (D-21).
     *
     * <p>C'est le seul endroit du projet qui connaît cette différence. Tout le
     * reste appelle {@link #urlPublique} et ignore si l'adresse obtenue est une
     * concaténation ou une signature temporaire.</p>
     */
    private final SignataireS3 signataire;

    /**
     * ⚠️ <b>Une variable VIDE n'est pas une variable ABSENTE.</b>
     *
     * <p>La première version se contentait de
     * {@code @Value("${GARAH_MEDIA_BASE_URL:" + DEFAUT + "}")}. Or Spring
     * n'applique la valeur par défaut que si la clé est <b>absente</b> — un
     * {@code GARAH_MEDIA_BASE_URL=} dans le {@code .env}, ligne présente mais
     * vide, produit une chaîne vide, pas le défaut.</p>
     *
     * <p>Découvert en lançant le jar : {@code /api/configuration} renvoyait
     * {@code "baseUrlMedias": ""}. Conséquence, si personne ne l'avait vu :</p>
     *
     * <pre>
     * attendu   https://f003.backblazeb2.com/file/garah-medias/produits/42.jpg
     * obtenu    /produits/42.jpg
     * </pre>
     *
     * <p>Un chemin relatif, résolu contre le domaine de l'API — qui ne sert
     * aucun fichier. <b>Toutes les images du site auraient été cassées, sans
     * la moindre erreur nulle part.</b> Un remplissage de formulaire incomplet
     * suffisait à provoquer la panne la plus visible du produit.</p>
     *
     * <p>D'où l'avertissement au démarrage : le repli est correct pour
     * développer, et catastrophique en production. Il doit se voir.</p>
     */
    public StockageObjet(@Value("${GARAH_MEDIA_BASE_URL:}") String baseUrl,
                         SignataireS3 signataire) {
        this.signataire = signataire;

        String valeur = sansBarreFinale(baseUrl);

        if (valeur.isBlank()) {
            // ⚠️ L'avertissement ne vaut que pour un bucket PUBLIC. Quand les
            // URL sont signées (D-21), le préfixe n'est jamais utilisé : le
            // signaler comme un oubli entraînerait à ignorer le message le
            // jour où il compte vraiment.
            if (!signataire.estActif()) {
                log.warn("GARAH_MEDIA_BASE_URL n'est pas renseignee : repli sur {}. "
                        + "En production, TOUTES les images seront introuvables.", DEFAUT);
            }
            valeur = DEFAUT;
        }

        this.baseUrl = valeur;
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

        String propre = cle.replaceFirst("^/+", "");

        // Bucket privé : une simple concaténation donnerait un 401 chez chaque
        // visiteur — sans la moindre erreur côté serveur. On signe (D-21).
        if (signataire.estActif()) {
            return signataire.url(propre);
        }

        return baseUrl + "/" + propre;
    }

    /** Le préfixe tel qu'il sera annoncé aux trois frontends. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Les URL de médias sont-elles signées, donc <b>temporaires</b> ?
     *
     * <p>Annoncé aux frontends par {@code /api/configuration}. Deux
     * comportements en dépendent :</p>
     *
     * <ul>
     *   <li>quand c'est {@code false}, le frontend peut construire lui-même une
     *       URL à partir de {@code baseUrlMedias} et d'une clé ;</li>
     *   <li>quand c'est {@code true}, il ne le peut <b>pas</b> — il faudrait
     *       la clé secrète — et il doit utiliser les URL complètes renvoyées
     *       par l'API, sans les mettre en cache au-delà de quelques jours.</li>
     * </ul>
     */
    public boolean urlsSignees() {
        return signataire.estActif();
    }

    private static boolean estAbsolue(String valeur) {
        return valeur.startsWith("http://") || valeur.startsWith("https://");
    }

    private static String sansBarreFinale(String valeur) {
        return valeur == null ? "" : valeur.replaceFirst("/+$", "");
    }
}
