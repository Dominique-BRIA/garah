package com.garah.api.commun;

import com.garah.api.commun.stockage.StockageObjet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un vrai test unitaire : aucune base, aucun contexte Spring, quelques
 * millisecondes.
 *
 * <p>C'est la règle du chapitre 20 appliquée : <i>ce qui peut être testé sans
 * infrastructure doit l'être sans infrastructure</i>. Cette classe est la
 * deuxième fonction vraiment pure du projet, après {@code Slug}.</p>
 */
@DisplayName("Reconstruction des URL de médias")
class StockageObjetTest {

    private static final String BASE = "https://f003.backblazeb2.com/file/garah-medias";

    private StockageObjet avecBase(String base) {
        return new StockageObjet(base);
    }

    @Test
    @DisplayName("une clé devient une URL absolue")
    void cleVersUrl() {
        assertThat(avecBase(BASE).urlPublique("produits/42/photo-1.jpg"))
                .isEqualTo(BASE + "/produits/42/photo-1.jpg");
    }

    @Test
    @DisplayName("une clé absente renvoie null, pas une exception")
    void cleAbsente() {
        StockageObjet stockage = avecBase(BASE);

        // Un produit sans photo est un cas NORMAL. Lever une exception ici
        // ferait échouer l'affichage d'une fiche pour une photo manquante.
        assertThat(stockage.urlPublique(null)).isNull();
        assertThat(stockage.urlPublique("")).isNull();
        assertThat(stockage.urlPublique("   ")).isNull();
    }

    @Test
    @DisplayName("la double barre oblique est évitée des deux côtés")
    void pasDeDoubleBarre() {
        // Pour S3, .../garah-medias//produits/42.jpg n'est pas le même objet
        // que .../garah-medias/produits/42.jpg. Le fichier serait introuvable.
        assertThat(avecBase(BASE + "/").urlPublique("/produits/42.jpg"))
                .isEqualTo(BASE + "/produits/42.jpg");

        assertThat(avecBase(BASE).urlPublique("///produits/42.jpg"))
                .isEqualTo(BASE + "/produits/42.jpg");
    }

    @Test
    @DisplayName("une clé déjà absolue est renvoyée telle quelle")
    void cleDejaAbsolue() {
        String ancienne = "https://ancien-hebergeur.example/img/7.jpg";

        // Cas des lignes importées d'un ancien système : les préfixer
        // produirait une URL invalide, et l'image disparaîtrait.
        assertThat(avecBase(BASE).urlPublique(ancienne)).isEqualTo(ancienne);
        assertThat(avecBase(BASE).urlPublique("http://exemple.test/a.png"))
                .isEqualTo("http://exemple.test/a.png");
    }

    /**
     * Le défaut trouvé en lançant le jar : {@code GARAH_MEDIA_BASE_URL=} dans
     * le {@code .env} — ligne présente, valeur vide.
     *
     * <p>Spring n'applique la valeur par défaut de {@code @Value} que si la clé
     * est <b>absente</b>. Sans le repli explicite, toutes les URL devenaient
     * des chemins relatifs, et toutes les images du site étaient cassées sans
     * qu'aucune erreur ne soit levée.</p>
     */
    @Test
    @DisplayName("une base vide se replie au lieu de produire un chemin relatif")
    void baseVideSeReplie() {
        for (String base : new String[]{"", "   ", null}) {
            String url = avecBase(base).urlPublique("produits/42.jpg");

            assertThat(url)
                    .as("base = [%s]", base)
                    .startsWith("http")
                    .endsWith("/produits/42.jpg");
        }
    }
}
