package com.garah.api.commerce;

import com.garah.api.commerce.domaine.ServicePaiementMobile;
import com.garah.api.commun.erreur.RegleMetierViolee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le numéro de téléphone envoyé à l'opérateur.
 *
 * <p>Test sans base ni réseau : c'est du calcul pur, et il doit rester
 * instantané. Le reste du paiement mobile ne peut pas être testé ici sans
 * appeler Campay — c'est la limite assumée de ce fichier.</p>
 *
 * <p>🎯 <b>Pourquoi ce détail mérite un test.</b> Les vraies personnes écrivent
 * leur numéro de six façons différentes. Un format trop strict fait échouer un
 * <b>paiement</b> pour un espace — et le client, lui, conclut que le site ne
 * marche pas.</p>
 */
@DisplayName("Normalisation du numéro mobile money")
class NormalisationTelephoneTest {

    @ParameterizedTest(name = "« {0} » devient {1}")
    @CsvSource({
            // Toutes ces écritures désignent le même abonné camerounais.
            "'+237 6 99 00 00 00', 237699000000",
            "'237699000000',       237699000000",
            "'699000000',          237699000000",
            "'00237699000000',     237699000000",
            "'(237) 699-000-000',  237699000000",
            "'237 699 000 000',    237699000000"
    })
    @DisplayName("accepte les écritures usuelles et produit la même valeur")
    void normaliseLesFormesUsuelles(String saisi, String attendu) {
        assertThat(ServicePaiementMobile.normaliserTelephone(saisi)).isEqualTo(attendu);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "12345", "abcdefghij", "+237", "6990000001234567890"})
    @DisplayName("refuse ce qui ne peut pas être un numéro exploitable")
    void refuseLInexploitable(String saisi) {
        // On refuse ICI plutôt que d'envoyer à l'opérateur : une demande de
        // collecte rejetée par Campay coûte un aller-retour réseau et produit
        // un message d'erreur bien moins clair.
        assertThatThrownBy(() -> ServicePaiementMobile.normaliserTelephone(saisi))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("un numéro absent est refusé explicitement")
    void refuseNull() {
        assertThatThrownBy(() -> ServicePaiementMobile.normaliserTelephone(null))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("obligatoire");
    }
}
