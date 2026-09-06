package com.garah.api.commun;

import com.garah.api.commun.stockage.SignataireS3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La signature des URL de médias, et surtout leur <b>stabilité</b>.
 *
 * <h2>Pourquoi la stabilité mérite un test à elle seule</h2>
 *
 * <p>Signer produit une chaîne différente à chaque appel — c'est normal, la
 * signature encode l'instant. Une implémentation naïve renverrait donc une
 * adresse neuve à chaque affichage de la fiche produit, et <b>rien ne le
 * signalerait</b> : les images s'afficheraient parfaitement.</p>
 *
 * <p>Trois choses casseraient en silence :</p>
 *
 * <ul>
 *   <li>le <b>cache navigateur</b> — une URL jamais revue est retéléchargée à
 *       chaque visite. Sur une connexion mobile camerounaise, ça se voit ;</li>
 *   <li>le <b>référencement</b> — {@code garah-web} existe pour le SEO (D-03),
 *       et aucun moteur n'indexe une image dont l'adresse change à chaque
 *       passage ;</li>
 *   <li>tout <b>CDN</b> placé devant plus tard.</li>
 * </ul>
 *
 * <p>C'est exactement le type de régression qu'une relecture ne voit pas et
 * qu'un test attrape. D'où ce fichier.</p>
 *
 * <p>Il se saute tout seul quand la signature n'est pas activée — en CI, par
 * exemple, où il n'y a aucun compte de stockage.</p>
 */
@SpringBootTest
@DisplayName("Signature des URL de medias")
class SignataireS3Test {

    @Autowired SignataireS3 signataire;

    @Test
    @DisplayName("deux appels sur la meme cle renvoient la MEME URL")
    void lUrlEstStable() {
        Assumptions.assumeTrue(signataire.estActif(),
                "GARAH_S3_URLS_SIGNEES n'est pas actif : test ignore.");

        String premiere = signataire.url("produits/42/photo-1.jpg");
        String seconde = signataire.url("produits/42/photo-1.jpg");

        assertThat(premiere).isNotNull().startsWith("http");

        // 🎯 Le cœur du test. Sans cache, ces deux chaînes différeraient — et
        // le catalogue serait retéléchargé en entier à chaque visite.
        assertThat(seconde)
                .as("une URL signee doit etre reutilisee tant qu'elle a de la marge")
                .isEqualTo(premiere);
    }

    @Test
    @DisplayName("deux cles differentes donnent deux URL differentes")
    void chaqueCleAsonUrl() {
        Assumptions.assumeTrue(signataire.estActif(),
                "GARAH_S3_URLS_SIGNEES n'est pas actif : test ignore.");

        // Garde-fou contre un cache trop zele qui renverrait la meme entree
        // pour tout le monde — toutes les fiches afficheraient la meme photo.
        assertThat(signataire.url("produits/1/a.jpg"))
                .isNotEqualTo(signataire.url("produits/2/b.jpg"));
    }

    @Test
    @DisplayName("l'URL signee porte bien la cle et une signature")
    void lUrlContientLaSignature() {
        Assumptions.assumeTrue(signataire.estActif(),
                "GARAH_S3_URLS_SIGNEES n'est pas actif : test ignore.");

        String url = signataire.url("produits/42/photo-1.jpg");

        assertThat(url).contains("produits/42/photo-1.jpg");

        // Les parametres de la signature AWS SigV4. Leur absence signifierait
        // qu'on a renvoye une URL nue — donc un 401 chez chaque visiteur.
        assertThat(url)
                .contains("X-Amz-Algorithm")
                .contains("X-Amz-Signature")
                .contains("X-Amz-Expires");
    }

    @Test
    @DisplayName("une cle absente renvoie null, pas une exception")
    void cleAbsente() {
        // Un produit sans photo est un cas NORMAL, y compris en mode signe.
        assertThat(signataire.url(null)).isNull();
        assertThat(signataire.url("")).isNull();
        assertThat(signataire.url("   ")).isNull();
    }

    @Test
    @DisplayName("un signataire non configure ne signe rien et ne plante pas")
    void inactifSansConfiguration() {
        // Le cas du bucket PUBLIC : StockageObjet doit pouvoir retomber sur la
        // concatenation sans avoir a tester quoi que ce soit lui-meme.
        SignataireS3 inactif = new SignataireS3("", "", "", "", "", false, 7);

        assertThat(inactif.estActif()).isFalse();
        assertThat(inactif.url("produits/42.jpg")).isNull();
    }

    @Test
    @DisplayName("activer la signature sans configurer le stockage reste inoffensif")
    void actifSansConfigurationResteInactif() {
        // Un .env a moitie rempli ne doit pas produire d'URL bancales : mieux
        // vaut retomber sur la concatenation, visiblement fausse, qu'une
        // signature calculee avec des cles vides.
        SignataireS3 malConfigure = new SignataireS3("", "", "", "", "", true, 7);

        assertThat(malConfigure.estActif()).isFalse();
    }
}
