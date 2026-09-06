package com.garah.api.iam.domaine;

import com.garah.api.commun.email.PasserelleEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le lien de confirmation doit être <b>absolu</b>.
 *
 * <h2>Le défaut que ce fichier verrouille</h2>
 *
 * <p>La première version fabriquait {@code /api/auth/verification?jeton=…}
 * quand aucune URL n'était configurée. C'est valide dans une page web, et
 * <b>totalement inutilisable dans un e-mail</b> : le client de messagerie n'a
 * aucune origine sur laquelle résoudre un chemin relatif.</p>
 *
 * <p>Le message partait, le journal disait « E-mail envoye », et le lien ne
 * menait nulle part. <b>Aucune erreur nulle part</b> — ni serveur, ni client.
 * Trouvé en suivant le lien pour de vrai, jamais par la relecture.</p>
 *
 * <p>Test unitaire pur : aucun contexte Spring. Fabriquer un lien est du
 * calcul de chaîne, et les 15 secondes de démarrage d'un
 * {@code @SpringBootTest} n'apporteraient rien.</p>
 */
@DisplayName("Lien de confirmation")
class LienDeVerificationTest {

    /**
     * Une passerelle qui se déclare non configurée.
     *
     * <p>Le constructeur du service la consulte uniquement pour décider s'il
     * doit avertir. Le fournisseur de {@code JavaMailSender} peut donc être
     * {@code null} : rien ne le déréférence tant qu'aucun message n'est
     * envoyé, et ce test n'en envoie aucun.</p>
     *
     * <p>Pas de Mockito : une sous-classe de trois lignes dit la même chose et
     * se lit sans connaître la bibliothèque.</p>
     */
    private static PasserelleEmail passerelleMuette() {
        return new PasserelleEmail(null, "", "", "GARAH", false) {
            @Override
            public boolean estConfigure() {
                return false;
            }
        };
    }

    private static ServiceVerificationEmail service(String urlVerification, String urlApi) {
        return new ServiceVerificationEmail(
                null, null, passerelleMuette(), 48, urlVerification, urlApi);
    }

    @Test
    @DisplayName("avec GARAH_URL_API, le lien pointe sur la route de l'API")
    void avecUrlApiLeLienEstAbsolu() {
        String lien = service("", "https://garah-api.exemple.dev").lienDe("abc123");

        assertThat(lien)
                .as("un lien relatif ne mene nulle part depuis une boite mail")
                .startsWith("https://")
                .isEqualTo("https://garah-api.exemple.dev/api/auth/verification?jeton=abc123");
    }

    @Test
    @DisplayName("GARAH_URL_VERIFICATION l'emporte : c'est la page du frontend")
    void lUrlDuFrontendEstPrioritaire() {
        String lien = service("https://app.garah.cm/confirmation",
                "https://garah-api.exemple.dev").lienDe("abc123");

        assertThat(lien).isEqualTo("https://app.garah.cm/confirmation?jeton=abc123");
    }

    @Test
    @DisplayName("la barre finale en trop ne produit pas de double barre")
    void laBarreFinaleEstToleree() {
        assertThat(service("", "https://garah-api.exemple.dev/").lienDe("abc"))
                .doesNotContain("dev//")
                .isEqualTo("https://garah-api.exemple.dev/api/auth/verification?jeton=abc");
    }

    /**
     * ⚠️ Le jeton est encodé.
     *
     * <p>Base64 URL-safe ne produit ni {@code +} ni {@code /}, donc le risque
     * est théorique aujourd'hui. Il cesserait de l'être le jour où la
     * fabrication du jeton changerait — et le symptôme serait un lien qui
     * fonctionne pour la plupart des inscrits et échoue pour quelques-uns.
     */
    @Test
    @DisplayName("un jeton contenant des caracteres speciaux est encode")
    void leJetonEstEncode() {
        String lien = service("", "https://api.exemple.dev").lienDe("a+b/c=d e");

        assertThat(lien).doesNotContain(" ");
        assertThat(lien).contains("jeton=a%2Bb%2Fc%3Dd+e");
    }
}
