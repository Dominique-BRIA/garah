package com.garah.api.commun.stockage;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.commun.erreur.RegleMetierViolee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dépose et supprime des fichiers sur Backblaze B2 (compatible S3).
 *
 * <p>{@link StockageObjet} sait <b>lire</b> — reconstruire l'URL publique d'une
 * clé. Cette classe-ci sait <b>écrire</b>. Les deux sont séparées parce qu'elles
 * n'ont ni les mêmes dépendances ni le même risque : la lecture ne demande
 * qu'une variable d'environnement, l'écriture exige des clés d'accès.</p>
 *
 * <p><b>Render n'a pas de disque persistant</b> (D-14) : aucun fichier n'est
 * jamais écrit localement, même temporairement. Le flux entrant part
 * directement vers B2.</p>
 *
 * <p>⚠️ Le jour de la bascule vers un MinIO sur le VPS, seules l'URL et les
 * clés changent — l'API S3 est la même. C'est exactement ce que D-14 appelle
 * la réversibilité, et c'est la raison du choix du SDK S3 plutôt que de l'API
 * native de Backblaze.</p>
 */
@Component
public class DepotFichiers {

    private static final Logger log = LoggerFactory.getLogger(DepotFichiers.class);

    /**
     * Ce qu'on accepte de recevoir, et l'extension qu'on écrit.
     *
     * <p>🎯 <b>Une liste blanche, jamais une liste noire.</b> Interdire
     * {@code .exe} et {@code .php} laisse passer tout ce à quoi on n'a pas
     * pensé — et il y en a toujours. On énumère ce qu'on accepte ; ce qui n'y
     * est pas est refusé, y compris ce qui n'existe pas encore.</p>
     *
     * <p>L'extension vient de <b>cette table</b>, jamais du nom de fichier
     * envoyé. Un fichier nommé {@code chat.jpg.html} ne doit pas produire une
     * clé {@code .html} : servi depuis notre domaine, il exécuterait du script
     * dans le navigateur du visiteur.</p>
     */
    private static final Map<String, String> TYPES_ACCEPTES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/avif", "avif",
            "video/mp4", "mp4",
            "video/webm", "webm",
            "application/pdf", "pdf");

    /** Les types qu'on sert en ligne. Le reste est forcé en téléchargement. */
    private static final Set<String> AFFICHABLES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/avif",
            "video/mp4", "video/webm");

    /** 25 Mo. Au-delà, l'offre gratuite de Render coupe la requête de toute façon. */
    private static final long TAILLE_MAX = 25L * 1024 * 1024;

    private final S3Client s3;
    private final String bucket;
    private final boolean configure;

    public DepotFichiers(@Value("${GARAH_S3_ENDPOINT:}") String endpoint,
                         @Value("${GARAH_S3_REGION:}") String region,
                         @Value("${GARAH_S3_BUCKET:}") String bucket,
                         @Value("${GARAH_S3_ACCESS_KEY:}") String cleAcces,
                         @Value("${GARAH_S3_SECRET_KEY:}") String cleSecrete) {

        this.bucket = bucket == null ? "" : bucket.strip();

        // Une variable VIDE n'est pas une variable ABSENTE : on teste le
        // contenu (la leçon de StockageObjet, qui avait cassé les images).
        this.configure = !vide(endpoint) && !vide(this.bucket)
                && !vide(cleAcces) && !vide(cleSecrete);

        if (!configure) {
            log.warn("Le stockage de fichiers n'est pas configure (GARAH_S3_*). "
                    + "Tout televersement repondra 503 : AUCUN produit ne pourra recevoir "
                    + "de photo.");
            this.s3 = null;
            return;
        }

        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint.strip()))
                // Backblaze annonce une region comme eu-central-003, que le SDK
                // ne connaît pas. Region.of l'accepte telle quelle : elle ne
                // sert qu'à la signature, pas au routage — c'est
                // endpointOverride qui décide où l'on va.
                .region(Region.of(vide(region) ? "us-east-1" : region.strip()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cleAcces.strip(), cleSecrete.strip())))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder()
                        // ⚠️ Indispensable hors d'AWS. En style « virtuel », le
                        // SDK vise https://<bucket>.<endpoint> — un hôte qui
                        // n'existe pas chez Backblaze. En style « chemin », il
                        // vise https://<endpoint>/<bucket>, ce qui marche.
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        log.info("Stockage de fichiers actif sur le bucket {}", this.bucket);
    }

    public boolean estConfigure() {
        return configure;
    }

    /** Le stockage est indisponible : {@code 503}, la faute n'est pas à l'appelant. */
    public static class StockageIndisponible extends ErreurMetier {
        StockageIndisponible(String message) {
            super("STOCKAGE_INDISPONIBLE", message);
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /**
     * Dépose un fichier et renvoie sa <b>clé d'objet</b>.
     *
     * <p>⚠️ Une clé, jamais une URL : la base ne stocke que
     * {@code produits/42/a3f9.jpg}. Stocker l'URL complète obligerait à
     * réécrire toutes les lignes de {@code media} le jour du changement
     * d'hébergeur (D-14).</p>
     *
     * <p>Le nom du fichier envoyé par le client est <b>entièrement jeté</b>.
     * On fabrique un UUID. Trois raisons, et chacune est une vraie faille :</p>
     * <ul>
     *   <li>un nom peut contenir {@code ../} et viser une autre clé ;</li>
     *   <li>un nom peut porter une extension trompeuse ;</li>
     *   <li>deux clients téléversant {@code photo.jpg} écraseraient le fichier
     *       l'un de l'autre.</li>
     * </ul>
     *
     * @param prefixe l'emplacement logique, par exemple {@code produits/42}
     * @param typeMime le type <b>déclaré</b> — voir l'avertissement ci-dessous
     */
    public String deposer(String prefixe, String typeMime, long taille, InputStream contenu) {
        exigerConfiguration();

        String type = typeMime == null ? "" : typeMime.toLowerCase(Locale.ROOT).strip();
        String extension = TYPES_ACCEPTES.get(type);

        if (extension == null) {
            throw new RegleMetierViolee("TYPE_FICHIER_REFUSE",
                    "Ce type de fichier n'est pas accepté. Formats admis : "
                    + "JPEG, PNG, WebP, AVIF, MP4, WebM, PDF.");
        }

        if (taille <= 0) {
            throw new RegleMetierViolee("FICHIER_VIDE", "Le fichier est vide.");
        }
        if (taille > TAILLE_MAX) {
            throw new RegleMetierViolee("FICHIER_TROP_VOLUMINEUX",
                    "Le fichier dépasse la taille maximale de 25 Mo.");
        }

        String cle = nettoyerPrefixe(prefixe) + "/" + UUID.randomUUID() + "." + extension;

        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(cle)
                            .contentType(type)
                            .contentLength(taille)
                            // ⚠️ Sans en-tête, un navigateur peut « deviner » le
                            // type d'un fichier et l'exécuter. nosniff le lui
                            // interdit, et attachment force le téléchargement
                            // de tout ce qui n'est pas une image ou une vidéo —
                            // un PDF affiché en ligne depuis notre domaine peut
                            // porter du script.
                            .contentDisposition(AFFICHABLES.contains(type)
                                    ? "inline" : "attachment")
                            .metadata(Map.of("x-amz-meta-origine", "garah"))
                            .build(),
                    RequestBody.fromInputStream(contenu, taille));

        } catch (S3Exception e) {
            // Le message d'AWS peut contenir la clé d'accès dans certains cas
            // d'erreur de signature : on journalise le code, jamais le message.
            log.error("Televersement refuse par le stockage (cle {}, code {})",
                    cle, e.awsErrorDetails() == null ? "?" : e.awsErrorDetails().errorCode());
            throw new StockageIndisponible("Le fichier n'a pas pu être enregistré.");
        }

        log.info("Fichier depose : {} ({} octets)", cle, taille);
        return cle;
    }

    /** Variante pratique quand on a déjà les octets en mémoire. */
    public String deposer(String prefixe, String typeMime, byte[] contenu) {
        return deposer(prefixe, typeMime, contenu.length,
                new java.io.ByteArrayInputStream(contenu));
    }

    /**
     * Supprime un fichier.
     *
     * <p>Ne lève pas si l'objet n'existe pas : S3 considère une suppression
     * d'objet absent comme réussie, et c'est le bon comportement — l'état visé
     * (« ce fichier n'existe plus ») est atteint.</p>
     */
    public void supprimer(String cleObjet) {
        exigerConfiguration();

        if (cleObjet == null || cleObjet.isBlank()) {
            return;
        }

        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(cleObjet.strip())
                    .build());
        } catch (S3Exception e) {
            log.error("Suppression impossible (cle {}, code {})", cleObjet,
                    e.awsErrorDetails() == null ? "?" : e.awsErrorDetails().errorCode());
            throw new StockageIndisponible("Le fichier n'a pas pu être supprimé.");
        }
    }

    /**
     * Le type MIME réel, déduit des premiers octets — pas de ce que le client déclare.
     *
     * <p>⚠️ <b>{@code Content-Type} est fourni par le client et se falsifie en
     * une ligne.</b> Un fichier HTML annoncé {@code image/png} passerait la
     * liste blanche, et serait servi depuis le domaine des médias.</p>
     *
     * <p>On relit donc les premiers octets (le « nombre magique »). Ce n'est
     * pas infaillible — on peut forger un fichier valide pour deux formats —
     * mais cela ferme le cas courant, et c'est ce qui compte.</p>
     *
     * @return le type détecté, ou {@code null} si aucun format connu ne correspond
     */
    public static String typeReel(byte[] debut) {
        if (debut == null || debut.length < 12) {
            return null;
        }

        // JPEG : FF D8 FF
        if (octets(debut, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        // PNG : 89 50 4E 47 0D 0A 1A 0A
        if (octets(debut, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        // PDF : %PDF
        if (octets(debut, 0, 0x25, 0x50, 0x44, 0x46)) {
            return "application/pdf";
        }

        // Les conteneurs ISO-BMFF et RIFF portent leur nature en position 4/8.
        String marque = new String(debut, 4, 8, java.nio.charset.StandardCharsets.US_ASCII);
        if (marque.startsWith("ftyp")) {
            return "video/mp4";
        }
        if (octets(debut, 0, 0x52, 0x49, 0x46, 0x46)) {   // RIFF
            String forme = new String(debut, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("WEBP".equals(forme)) {
                return "image/webp";
            }
        }
        // WebM / Matroska : 1A 45 DF A3
        if (octets(debut, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return "video/webm";
        }

        return null;
    }

    /** Lit au plus {@code n} octets sans consommer le reste du flux. */
    public static byte[] premiersOctets(InputStream flux, int n) throws IOException {
        byte[] tampon = new byte[n];
        int lus = flux.readNBytes(tampon, 0, n);
        if (lus == n) {
            return tampon;
        }
        byte[] exact = new byte[lus];
        System.arraycopy(tampon, 0, exact, 0, lus);
        return exact;
    }

    // -------------------------------------------------------------------------

    private void exigerConfiguration() {
        if (!configure) {
            throw new StockageIndisponible(
                    "Le stockage de fichiers n'est pas configuré sur ce serveur.");
        }
    }

    /**
     * Empêche une clé de sortir de son emplacement.
     *
     * <p>Un préfixe {@code produits/../../secrets} viserait un autre dossier du
     * bucket. Le préfixe vient toujours de notre code, jamais du client — mais
     * une règle de sécurité qui repose sur « personne ne fera l'erreur » est
     * une règle qui tombera.</p>
     */
    private static String nettoyerPrefixe(String prefixe) {
        if (prefixe == null || prefixe.isBlank()) {
            return "divers";
        }
        String propre = prefixe.strip()
                .replace("\\", "/")
                .replaceAll("\\.{2,}", "")
                .replaceAll("[^A-Za-z0-9/_-]", "")
                .replaceAll("/+", "/")
                .replaceAll("^/|/$", "");

        return propre.isBlank() ? "divers" : propre;
    }

    private static boolean octets(byte[] donnees, int decalage, int... attendus) {
        if (donnees.length < decalage + attendus.length) {
            return false;
        }
        for (int i = 0; i < attendus.length; i++) {
            if ((donnees[decalage + i] & 0xFF) != attendus[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}
