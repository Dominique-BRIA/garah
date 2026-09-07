package com.garah.api.catalogue;

import com.garah.api.catalogue.domaine.ReferenceProduit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitaire pur : aucune base, aucun contexte Spring.
 *
 * <p>La fabrication d'une référence est une fonction — mêmes entrées, même
 * sortie. La tester à travers un {@code @SpringBootTest} coûterait des
 * secondes pour vérifier ce qui se vérifie en microsecondes.</p>
 */
@DisplayName("Fabrication des références produit")
class ReferenceProduitTest {

    @ParameterizedTest
    @DisplayName("assemble le marchand, la catégorie et le nom")
    @CsvSource({
            "202020,     Chaussure,   Adidas,           202020-CHA-ADIDAS",
            "MAR-00042,  Chaussure,   Adidas,           MAR00042-CHA-ADIDAS",
            "202020,     Vêtements,   Chemise Oxford,   202020-VET-CHEMISE-OXFORD",
            "202020,     T-shirts,    Col rond,         202020-TSH-COL-ROND",
    })
    void assemble(String code, String categorie, String nom, String attendu) {
        assertThat(ReferenceProduit.de(code, categorie, nom)).isEqualTo(attendu);
    }

    @Test
    @DisplayName("retire les accents plutôt que les caractères")
    void retireLesAccents() {
        // « é » doit devenir « E », pas disparaître : « DEVERROUILLE » se lit,
        // « DVERROUILL » ne se lit plus.
        assertThat(ReferenceProduit.de("202020", "Téléphonie", "Téléphone déverrouillé"))
                .isEqualTo("202020-TEL-TELEPHONE-DEVERROUILLE");
    }

    @Test
    @DisplayName("ne laisse jamais un tiret pendre après la coupe")
    void pasDeTiretEnFin() {
        // Le nom est coupé à 24 caractères, ce qui tomberait ici pile sur un
        // séparateur : « ...-AB- » n'est pas une référence, c'est une phrase
        // interrompue.
        String reference = ReferenceProduit.de("M", "Cat", "Aaaaaaaaaaaaaaaaaaaaaaa Bcd");

        assertThat(reference).doesNotEndWith("-");
        assertThat(reference).isEqualTo("M-CAT-AAAAAAAAAAAAAAAAAAAAAAA");
    }

    @Test
    @DisplayName("tient dans la colonne, suffixe compris")
    void tientDansLaColonne() {
        // La colonne accepte 50 caractères, et le service peut encore ajouter
        // « -999 » pour lever une collision : la base doit rester sous 46.
        String reference = ReferenceProduit.de(
                "CODEMARCHANDTRESLONG", "Categorie", "Un nom de produit vraiment tres long");

        assertThat(reference.length()).isLessThanOrEqualTo(46);
    }

    @Test
    @DisplayName("se rabat sur un mot lisible quand il ne reste rien")
    void repliQuandRienNeReste() {
        // Un nom entierement fait de caracteres non latins ne laisse aucune
        // lettre : mieux vaut « PRODUIT » qu'une reference finissant par un
        // tiret solitaire, que la base refuserait de toute facon.
        assertThat(ReferenceProduit.de(null, "", "北京"))
                .isEqualTo("MAR-GEN-PRODUIT");
    }
}
