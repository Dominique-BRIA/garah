package com.garah.api.catalogue;

import com.garah.api.catalogue.domaine.Slug;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test unitaire pur : aucune base, aucun contexte Spring.
 *
 * <p>Il tourne en quelques millisecondes, là où un {@code @SpringBootTest} en
 * demande plusieurs secondes. C'est la règle : <b>ce qui peut être testé sans
 * infrastructure doit l'être sans infrastructure</b>. Une suite lente finit
 * par ne plus être lancée.</p>
 */
@DisplayName("Fabrication des slugs")
class SlugTest {

    @ParameterizedTest
    @CsvSource({
            "Chemise Oxford,            chemise-oxford",
            "Chemise Oxford — Bleu,     chemise-oxford-bleu",
            "Téléphone déverrouillé,    telephone-deverrouille",
            "Sac  de   ciment 50 kg,    sac-de-ciment-50-kg",
            "  Espaces autour  ,        espaces-autour",
            "Écran 27\" 4K,             ecran-27-4k",
            "Café & Thé,                cafe-the"
    })
    @DisplayName("les accents et la ponctuation disparaissent proprement")
    void fabrication(String entree, String attendu) {
        assertThat(Slug.de(entree)).isEqualTo(attendu);
    }

    @Test
    @DisplayName("les accents deviennent des lettres, ils ne sont pas supprimes")
    void lesAccentsDeviennentDesLettres() {
        // Le piege : une normalisation naive donnerait « tlphone ».
        assertThat(Slug.de("Téléphone")).isEqualTo("telephone");
        assertThat(Slug.de("Crème brûlée")).isEqualTo("creme-brulee");
    }

    @Test
    @DisplayName("un texte vide est refuse")
    void texteVide() {
        assertThatThrownBy(() -> Slug.de("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
