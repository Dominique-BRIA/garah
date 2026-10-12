package com.garah.api.commerce.infra;

import com.garah.api.commerce.infra.PasserellePaiement.DemandeEncaissement;
import com.garah.api.commerce.infra.PasserellePaiement.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * La passerelle GeniusPay, contre les réponses TELLES QUE LEUR DOCUMENTATION
 * LES MONTRE (docs.geniuspay.ci, version 2.0.0, lue le 12/10/2026).
 */
@DisplayName("Passerelle GeniusPay")
class ClientGeniusPayTest {

    private static final String CLE = "sk_live_cle_de_test";
    private static final String SECRET = "whsec_secret_de_test";

    private MockRestServiceServer serveur;
    private ClientGeniusPay client;

    @BeforeEach
    void preparer() {
        RestClient.Builder constructeur = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(constructeur).build();
        client = new ClientGeniusPay(CLE, "", SECRET, constructeur);
    }

    private static DemandeEncaissement demande() {
        return new DemandeEncaissement(41L, 7L, "GAR-2026-000007", new BigDecimal("15000.00"),
                "237699000000", "Awa Ngono", "https://www.garah.me/paiement/7?paiement=41");
    }

    @Test
    @DisplayName("crée la session en XAF, la clé en en-tête, et rend la page de paiement")
    void creeLaSession() {
        serveur.expect(requestTo(ClientGeniusPay.BASE_PAR_DEFAUT + "/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", CLE))
                // Le même paiement donne toujours la même clé d'idempotence.
                .andExpect(header("Idempotency-Key", ClientGeniusPay.cleIdempotence(41L)))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.currency").value("XAF"))
                .andExpect(jsonPath("$.customer.phone").value("+237699000000"))
                .andExpect(jsonPath("$.customer.name").value("Awa Ngono"))
                .andExpect(jsonPath("$.return_url").value("https://www.garah.me/paiement/7?paiement=41"))
                // Pas de moyen imposé : le client choisit sur la page (D-55).
                .andExpect(jsonPath("$.payment_method").doesNotExist())
                .andRespond(withSuccess("""
                        {"success": true, "data": {"reference": "PAY-260813-908123", "amount": 15000,
                         "currency": "XAF", "status": "pending",
                         "payment_url": "https://pay.genius.ci/checkout/PAY-260813-908123"}}
                        """, MediaType.APPLICATION_JSON));

        var collecte = client.encaisser(demande());

        assertThat(collecte.reference()).isEqualTo("PAY-260813-908123");
        assertThat(collecte.urlPaiement()).isEqualTo("https://pay.genius.ci/checkout/PAY-260813-908123");
        serveur.verify();
    }

    @Test
    @DisplayName("⚠️ un refus de l'opérateur est rapporté tel quel, pas maquillé en panne")
    void refusRapporte() {
        serveur.expect(requestTo(ClientGeniusPay.BASE_PAR_DEFAUT + "/payments"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\": false, \"message\": \"Compte non autorise en Live\"}"));

        assertThatThrownBy(() -> client.encaisser(demande()))
                .isInstanceOf(PasserellePaiement.OperateurRefuse.class)
                .hasMessageContaining("Compte non autorise en Live");
    }

    @Test
    @DisplayName("lit l'état d'un paiement réussi, avec son moyen")
    void litUnPaiementReussi() {
        serveur.expect(requestTo(ClientGeniusPay.BASE_PAR_DEFAUT + "/payments/PAY-260813-908123/status"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", CLE))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"reference": "PAY-260813-908123", "amount": 15000,
                         "currency": "XAF", "status": "completed", "payment_method": "orange_money_cm",
                         "paid_at": "2026-08-13T14:32:10Z"}}
                        """, MediaType.APPLICATION_JSON));

        var etat = client.statut("PAY-260813-908123").orElseThrow();

        assertThat(etat.reussi()).isTrue();
        assertThat(etat.montant()).isEqualByComparingTo("15000");
        assertThat(etat.operateur()).isEqualTo("orange_money_cm");
    }

    @Test
    @DisplayName("⚠️ une référence forgée ne change pas la route interrogée")
    void referenceForgee() {
        serveur.expect(requestTo(ClientGeniusPay.BASE_PAR_DEFAUT + "/payments/..%2Fadmin/status"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.statut("../admin")).isEmpty();
        serveur.verify();
    }

    @ParameterizedTest(name = "« {0} » → {1}")
    @CsvSource({
            "completed, REUSSIE",
            "COMPLETED, REUSSIE",
            "pending,   EN_COURS",
            "failed,    ECHOUEE",
            "refunded,  ECHOUEE",
            "inconnu,   EN_COURS"
    })
    @DisplayName("traduit le vocabulaire de GeniusPay")
    void vocabulaire(String statut, Issue attendue) {
        assertThat(ClientGeniusPay.issueDe(statut)).isEqualTo(attendue);
    }

    @Test
    @DisplayName("🎯 la signature se vérifie sur le corps brut, avec le secret webhook")
    void signature() throws Exception {
        String corps = "{\"event\":\"payment.succeeded\",\"data\":{\"reference\":\"PAY-1\"}}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String bonne = HexFormat.of().formatHex(mac.doFinal(corps.getBytes(StandardCharsets.UTF_8)));

        assertThat(client.signatureValide(corps, bonne)).isTrue();
        assertThat(client.signatureValide(corps, bonne.toUpperCase())).isTrue();
        // Un octet changé dans le corps, et la signature ne tient plus.
        assertThat(client.signatureValide(corps.replace("PAY-1", "PAY-2"), bonne)).isFalse();
        assertThat(client.signatureValide(corps, null)).isFalse();
    }

    @Test
    @DisplayName("⚠️ une clé publique (pk_) n'est pas une clé configurée")
    void clePublique() {
        var mauvaise = new ClientGeniusPay("pk_live_xxx", "", "", RestClient.builder());
        assertThat(mauvaise.estConfigure()).isFalse();
        assertThatThrownBy(() -> mauvaise.encaisser(demande()))
                .isInstanceOf(PasserellePaiement.OperateurIndisponible.class);
    }

    @Test
    @DisplayName("une clé sk_sandbox_ est un bac à sable, une clé sk_live_ non")
    void bacASable() {
        assertThat(new ClientGeniusPay("sk_sandbox_x", "", "", RestClient.builder()).estDemonstration()).isTrue();
        assertThat(client.estDemonstration()).isFalse();
    }
}
