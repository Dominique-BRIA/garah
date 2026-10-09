package com.garah.api.commerce.domaine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le réglage {@code GARAH_PAIEMENT_FOURNISSEUR}, et la traduction du moyen
 * annoncé par un fournisseur (D-55).
 */
@DisplayName("Choix du fournisseur de paiement")
class ChoixDuFournisseurTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("absent : Campay, comme avant D-55")
    void parDefautCampay(String valeur) {
        assertThat(ServicePaiementMobile.fournisseurActif(valeur))
                .isEqualTo(FournisseurPaiement.CAMPAY);
    }

    @Test
    @DisplayName("la casse et les espaces ne comptent pas")
    void casse() {
        assertThat(ServicePaiementMobile.fournisseurActif(" moneyfusion "))
                .isEqualTo(FournisseurPaiement.MONEYFUSION);
    }

    @Test
    @DisplayName("⚠️ une valeur inconnue empêche de démarrer, plutôt que retomber sur Campay")
    void valeurInconnue() {
        // Une faute de frappe ferait sinon encaisser, en bac à sable, chez un
        // fournisseur que personne n'a choisi — sans que rien ne le signale.
        assertThatThrownBy(() -> ServicePaiementMobile.fournisseurActif("MONEY_FUSION"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAMPAY, MONEYFUSION");
    }

    @ParameterizedTest(name = "« {0} » → {1}")
    @CsvSource({
            "orange,              ORANGE_MONEY",
            "orange-cm,           ORANGE_MONEY",
            "Orange Money,        ORANGE_MONEY",
            "mtn,                 MTN_MOMO",
            "mtn-cm,              MTN_MOMO",
            "MTN MoMo,            MTN_MOMO"
    })
    @DisplayName("traduit le moyen annoncé, quelle que soit son écriture")
    void moyenConnu(String annonce, MoyenPaiement attendu) {
        assertThat(ServicePaiementMobile.moyenDe(annonce)).isEqualTo(attendu);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wave", "moov"})
    @DisplayName("⚠️ un moyen qu'on ne sait pas nommer reste inconnu — sans bloquer la confirmation")
    void moyenInconnu(String annonce) {
        assertThat(ServicePaiementMobile.moyenDe(annonce)).isNull();
    }
}
