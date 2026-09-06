package com.garah.api.commun;

import com.garah.api.commun.stockage.DepotFichiers;
import com.garah.api.commun.stockage.StockageObjet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'aller-retour RÉEL avec le stockage d'objets — dépôt, lecture publique, suppression.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * <p>{@code DepotFichiersTest} vérifie la détection de type sur des octets en
 * mémoire : c'est du calcul, et ça ne prouve rien sur la configuration. Or
 * <b>tout ce qui casse en pratique est dans la configuration</b> :</p>
 *
 * <ul>
 *   <li>la clé maîtresse Backblaze, qui n'est <b>pas</b> acceptée par l'API
 *       S3 — il faut une clé d'application créée à la main ;</li>
 *   <li>le style d'URL : sans {@code pathStyleAccessEnabled}, le SDK vise
 *       {@code https://<bucket>.<endpoint>}, un hôte qui n'existe pas ;</li>
 *   <li>la région, qui ne sert qu'à la signature mais que Backblaze vérifie ;</li>
 *   <li>et surtout le bucket <b>PRIVÉ</b> alors qu'on le croit public.</li>
 * </ul>
 *
 * <p>🎯 <b>Le dernier point est le plus vicieux.</b> Un bucket privé accepte
 * parfaitement les téléversements : l'API S3 est authentifiée, donc tout
 * fonctionne côté back-office. Ce n'est qu'au moment où un <b>visiteur</b>
 * charge la fiche produit que les images répondent 401 — et rien, dans aucun
 * journal serveur, ne le signale. C'est exactement le genre de panne qu'on
 * découvre après la mise en ligne.</p>
 *
 * <p>D'où l'étape 2 : on relit l'URL publique <b>sans aucune authentification</b>,
 * exactement comme le ferait le navigateur d'un visiteur.</p>
 *
 * <h2>Ce test se saute tout seul</h2>
 *
 * <p>Tant que {@code GARAH_S3_*} n'est pas renseigné, il est ignoré — la CI
 * n'a pas de compte de stockage et n'en aura pas. Il ne s'exécute que sur un
 * poste réellement configuré, et c'est là qu'il a de la valeur.</p>
 */
@SpringBootTest
@DisplayName("Stockage d'objets — aller-retour reel")
class StockageReelTest {

    /** Un PNG 1×1 valide, le plus petit fichier qui passe la detection de type. */
    private static final byte[] PNG_MINIMAL = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
    };

    @Autowired DepotFichiers depot;
    @Autowired StockageObjet urls;

    @Test
    @DisplayName("un fichier deposé est lisible publiquement, puis supprimable")
    void allerRetourComplet() throws Exception {
        // Ni echec ni faux succes : le test se declare simplement « non
        // applicable » quand le stockage n'est pas configure.
        Assumptions.assumeTrue(depot.estConfigure(),
                "GARAH_S3_* n'est pas renseigne : test ignore.");

        // --- 1. Deposer -------------------------------------------------------
        String cle = depot.deposer("verification", "image/png", PNG_MINIMAL);

        assertThat(cle)
                .as("la cle doit rester relative : la base ne stocke jamais une URL (D-14)")
                .startsWith("verification/")
                .endsWith(".png")
                .doesNotContain("http");

        try {
            // --- 2. Relire SANS authentification ------------------------------
            //
            // ⚠️ C'est l'etape qui compte. Un bucket PRIVE laisse passer
            // l'etape 1 sans broncher — l'API S3 est authentifiee. Seul ce
            // GET anonyme distingue « bucket public » de « bucket prive »,
            // et c'est la difference entre un catalogue qui s'affiche et un
            // catalogue dont toutes les images repondent 401.
            String url = urls.urlPublique(cle);
            assertThat(url).as("l'URL publique doit etre absolue").startsWith("http");

            HttpResponse<byte[]> reponse = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(15))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());

            assertThat(reponse.statusCode())
                    .as("""
                        %d sur %s.
                        401/403 => le bucket est PRIVE, ou GARAH_MEDIA_BASE_URL est faux.
                        404     => GARAH_MEDIA_BASE_URL ne pointe pas sur ce bucket.
                        """.formatted(reponse.statusCode(), url))
                    .isEqualTo(200);

            assertThat(reponse.body())
                    .as("le fichier relu doit etre identique a celui depose")
                    .isEqualTo(PNG_MINIMAL);

            assertThat(reponse.headers().firstValue("content-type"))
                    .as("""
                        le type doit etre conserve : sans lui, le navigateur \
                        telecharge l'image au lieu de l'afficher""")
                    .hasValue("image/png");

        } finally {
            // --- 3. Nettoyer --------------------------------------------------
            // Dans un finally : un test qui echoue ne doit pas laisser de
            // dechets dans le bucket a chaque execution.
            depot.supprimer(cle);
        }
    }

    @Test
    @DisplayName("un type de fichier non autorise est refuse avant tout depot")
    void refuseUnTypeNonAutorise() {
        Assumptions.assumeTrue(depot.estConfigure(),
                "GARAH_S3_* n'est pas renseigne : test ignore.");

        // Le controle a lieu AVANT l'appel reseau : rien ne part sur le bucket.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        depot.deposer("verification", "text/html", "<script>".getBytes()))
                .isInstanceOf(com.garah.api.commun.erreur.RegleMetierViolee.class);
    }
}
