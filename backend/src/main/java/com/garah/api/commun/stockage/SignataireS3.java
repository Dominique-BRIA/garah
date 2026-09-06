package com.garah.api.commun.stockage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabrique des URL <b>signées</b> pour lire un objet d'un bucket privé.
 *
 * <h2>Pourquoi cette classe existe</h2>
 *
 * <p>Un bucket public se lit par simple concaténation :
 * {@code <base>/<clé>}. Un bucket <b>privé</b> refuse tout accès anonyme —
 * {@code 401} — et la seule façon de laisser un navigateur lire un objet sans
 * lui confier nos clés est de lui donner une URL <b>déjà signée</b>, valable un
 * temps limité.</p>
 *
 * <p>C'est la conséquence directe de D-21 : sans moyen de paiement, Backblaze
 * n'autorise pas de bucket public. Le choix est assumé, mais il déplace une
 * responsabilité — <b>le frontend ne peut plus construire les URL lui-même</b>,
 * puisqu'il faudrait lui livrer la clé secrète. L'API doit donc renvoyer des
 * adresses complètes.</p>
 *
 * <h2>🎯 Pourquoi les URL sont mises en cache</h2>
 *
 * <p>Signer est bon marché (un HMAC), mais chaque signature produit une
 * <b>chaîne différente</b>. Sans cache, la même photo changerait d'adresse à
 * chaque appel — et trois choses casseraient d'un coup :</p>
 *
 * <ul>
 *   <li>le <b>cache du navigateur</b> : une URL jamais revue est toujours
 *       retéléchargée. Sur une connexion mobile camerounaise, ça se voit ;</li>
 *   <li>le <b>référencement</b> : {@code garah-web} existe pour le SEO (D-03),
 *       et un moteur n'indexe pas des images dont l'adresse change à chaque
 *       visite ;</li>
 *   <li>tout <b>CDN</b> qu'on mettrait devant plus tard.</li>
 * </ul>
 *
 * <p>On garde donc la même URL signée tant qu'il lui reste une marge
 * confortable, et on ne la renouvelle qu'à l'approche de son expiration. Une
 * adresse rendue à un navigateur est ainsi <b>toujours valable au moins
 * {@link #MARGE_RENOUVELLEMENT}</b>.</p>
 *
 * <p>⚠️ Ça ne supprime pas le défaut de fond : une page HTML archivée plus de
 * sept jours contiendra des images mortes. C'est le prix du bucket privé, et il
 * disparaît le jour où le bucket devient public — sans changer une ligne de
 * code, seulement {@code GARAH_S3_URLS_SIGNEES=false}.</p>
 */
@Component
public class SignataireS3 {

    private static final Logger log = LoggerFactory.getLogger(SignataireS3.class);

    /**
     * Durée maximale d'une signature AWS SigV4 : <b>sept jours</b>.
     *
     * <p>Ce n'est pas un réglage : c'est une limite du protocole. Demander plus
     * produit une signature que le serveur refusera.</p>
     */
    private static final Duration DUREE_MAX = Duration.ofDays(7);

    /**
     * On renouvelle une URL quand il lui reste moins que ça.
     *
     * <p>Garantit qu'une adresse remise à un navigateur vit encore au moins
     * vingt-quatre heures — le temps qu'une page reste ouverte, qu'un onglet
     * dorme, ou qu'un cache la conserve.</p>
     */
    private static final Duration MARGE_RENOUVELLEMENT = Duration.ofDays(1);

    /**
     * Plafond du cache.
     *
     * <p>Un catalogue de dix mille photos ferait dix mille entrées d'environ
     * un demi-kilo-octet : quelques mégaoctets, acceptable. Mais un cache
     * <b>sans plafond</b> est une fuite de mémoire qui attend son heure — ici
     * il suffirait qu'un robot parcoure des clés inexistantes.</p>
     */
    private static final int TAILLE_MAX_CACHE = 20_000;

    private final S3Presigner signataire;
    private final String bucket;
    private final Duration duree;
    private final boolean actif;

    /** clé d'objet → URL signée et sa péremption. */
    private final Map<String, UrlSignee> cache = new ConcurrentHashMap<>();

    private record UrlSignee(String url, Instant expireLe) {
        boolean utilisableEncore() {
            return Instant.now().isBefore(expireLe.minus(MARGE_RENOUVELLEMENT));
        }
    }

    public SignataireS3(@Value("${GARAH_S3_ENDPOINT:}") String endpoint,
                        @Value("${GARAH_S3_REGION:}") String region,
                        @Value("${GARAH_S3_BUCKET:}") String bucket,
                        @Value("${GARAH_S3_ACCESS_KEY:}") String cleAcces,
                        @Value("${GARAH_S3_SECRET_KEY:}") String cleSecrete,
                        @Value("${GARAH_S3_URLS_SIGNEES:false}") boolean urlsSignees,
                        @Value("${GARAH_S3_SIGNATURE_JOURS:7}") long jours) {

        this.bucket = bucket == null ? "" : bucket.strip();

        boolean configure = !vide(endpoint) && !vide(this.bucket)
                && !vide(cleAcces) && !vide(cleSecrete);

        this.actif = urlsSignees && configure;

        // Sept jours maximum : au-delà, la signature est rejetée par le serveur.
        Duration demandee = Duration.ofDays(Math.max(1, jours));
        this.duree = demandee.compareTo(DUREE_MAX) > 0 ? DUREE_MAX : demandee;

        if (!actif) {
            this.signataire = null;
            if (urlsSignees) {
                log.warn("GARAH_S3_URLS_SIGNEES=true mais le stockage n'est pas configure : "
                        + "les URL de medias resteront de simples concatenations.");
            }
            return;
        }

        this.signataire = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint.strip()))
                .region(Region.of(vide(region) ? "us-east-1" : region.strip()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cleAcces.strip(), cleSecrete.strip())))
                // Même contrainte que pour l'écriture : hors d'AWS, le style
                // « virtuel » vise un hôte qui n'existe pas.
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        log.info("URL de medias SIGNEES (bucket prive), validite {} jours.", duree.toDays());
    }

    /** Faut-il signer ? Faux quand le bucket est public — le cas le plus simple. */
    public boolean estActif() {
        return actif;
    }

    /**
     * L'URL signée d'un objet, réutilisée tant qu'elle a de la marge.
     *
     * <p>Thread-safe : {@code compute} sérialise les appels concurrents sur une
     * même clé, donc deux requêtes simultanées sur la même photo produisent
     * une seule signature — et surtout la <b>même</b> adresse.</p>
     */
    public String url(String cleObjet) {
        if (!actif || cleObjet == null || cleObjet.isBlank()) {
            return null;
        }

        // Un cache sans plafond est une fuite de mémoire qui attend son heure.
        // On repart de zéro plutôt que d'implémenter une éviction LRU : les URL
        // se resignent en quelques microsecondes, la perte est négligeable.
        if (cache.size() > TAILLE_MAX_CACHE) {
            log.info("Cache d'URL signees plafonne ({} entrees) : remise a zero.", cache.size());
            cache.clear();
        }

        UrlSignee signee = cache.compute(cleObjet.strip(), (cle, existante) ->
                (existante != null && existante.utilisableEncore()) ? existante : signer(cle));

        return signee == null ? null : signee.url();
    }

    private UrlSignee signer(String cle) {
        var demande = GetObjectPresignRequest.builder()
                .signatureDuration(duree)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(cle)
                        .build())
                .build();

        var signee = signataire.presignGetObject(demande);
        return new UrlSignee(signee.url().toString(), Instant.now().plus(duree));
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}
