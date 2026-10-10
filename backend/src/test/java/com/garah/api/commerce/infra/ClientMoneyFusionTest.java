package com.garah.api.commerce.infra;

import com.garah.api.commerce.infra.PasserellePaiement.DemandeEncaissement;
import com.garah.api.commerce.infra.PasserellePaiement.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * La passerelle MoneyFusion, contre les réponses TELLES QUE LA DOCUMENTATION
 * LES MONTRE (docs.moneyfusion.net/fr/webapi, lue le 09/10/2026).
 *
 * <p>Aucun réseau : un faux serveur rejoue les exemples de la documentation.
 * Si MoneyFusion change son format, c'est le premier paiement réel qui le
 * dira — ces tests disent seulement que nous lisons bien ce qu'ils publient.</p>
 */
@DisplayName("Passerelle MoneyFusion")
class ClientMoneyFusionTest {

    private static final String LIEN = "https://www.pay.moneyfusion.net/GARAH/abc123/pay/";

    private MockRestServiceServer serveur;
    private ClientMoneyFusion client;

    @BeforeEach
    void preparer() {
        RestClient.Builder constructeur = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(constructeur).build();
        client = new ClientMoneyFusion(LIEN, "", "https://api.garah.test/api/paiements/notifications/moneyfusion", "",
                constructeur);
    }

    private static DemandeEncaissement demande() {
        return new DemandeEncaissement(41L, 7L, "GAR-2026-000007", new BigDecimal("15000.00"),
                "237699000000", "Awa Ngono", "https://garah.test/paiement/7?paiement=41");
    }

    @Test
    @DisplayName("crée la session avec le montant de la commande, et rend la page de paiement")
    void creeLaSession() {
        serveur.expect(requestTo(LIEN))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Le XAF n'a pas de centimes : 15000, jamais 15000.00.
                .andExpect(jsonPath("$.totalPrice").value(15000))
                .andExpect(jsonPath("$.numeroSend").value("699000000"))
                .andExpect(jsonPath("$.nomclient").value("Awa Ngono"))
                .andExpect(jsonPath("$.personal_Info[0].paiementId").value(41))
                .andExpect(jsonPath("$.return_url").value("https://garah.test/paiement/7?paiement=41"))
                .andExpect(jsonPath("$.webhook_url")
                        .value("https://api.garah.test/api/paiements/notifications/moneyfusion"))
                .andRespond(withSuccess("""
                        {"statut": true, "token": "0d1d8bc9b6d2819c", "message": "paiement en cours",
                         "url": "https://payin.moneyfusion.net/payment/0d1d8bc9b6d2819c/Marchand"}
                        """, MediaType.APPLICATION_JSON));

        var collecte = client.encaisser(demande());

        assertThat(collecte.reference()).isEqualTo("0d1d8bc9b6d2819c");
        assertThat(collecte.urlPaiement())
                .isEqualTo("https://payin.moneyfusion.net/payment/0d1d8bc9b6d2819c/Marchand");
        assertThat(collecte.codeUssd()).as("pas de code USSD : c'est Campay").isNull();
        serveur.verify();
    }

    @Test
    @DisplayName("⚠️ « statut: false » avec un 200 est un REFUS, et son message est rapporté")
    void statutFauxEstUnRefus() {
        serveur.expect(requestTo(LIEN))
                .andRespond(withSuccess("""
                        {"statut": false, "message": "IP non autorisee (20.111.1.11)"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.encaisser(demande()))
                .isInstanceOf(PasserellePaiement.OperateurRefuse.class)
                .hasMessageContaining("IP non autorisee");
    }

    @Test
    @DisplayName("⚠️ une réponse sans page de paiement ne laisse pas le client devant rien")
    void sansPageDePaiement() {
        serveur.expect(requestTo(LIEN))
                .andRespond(withSuccess("""
                        {"statut": true, "token": "abc"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.encaisser(demande()))
                .isInstanceOf(PasserellePaiement.OperateurIndisponible.class);
    }

    @Test
    @DisplayName("lit l'état d'un paiement réussi, frais compris")
    void litUnPaiementReussi() {
        serveur.expect(requestTo(ClientMoneyFusion.URL_STATUT_PAR_DEFAUT + "0d1d8bc9b6d2819c"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"statut": true, "message": "details paiement",
                         "data": {"_id": "65df163b11ab882694573060", "tokenPay": "0d1d8bc9b6d2819c",
                                  "numeroSend": "01010101", "nomclient": "John Doe",
                                  "numeroTransaction": "0101010101", "Montant": 194, "frais": 6,
                                  "statut": "paid", "moyen": "orange",
                                  "createdAt": "2024-02-28T11:17:15.285Z"}}
                        """, MediaType.APPLICATION_JSON));

        var etat = client.statut("0d1d8bc9b6d2819c").orElseThrow();

        assertThat(etat.reussi()).isTrue();
        assertThat(etat.montant()).isEqualByComparingTo("200");
        assertThat(etat.operateur()).isEqualTo("orange");
        assertThat(etat.referenceOperateur()).isEqualTo("0101010101");
    }

    @Test
    @DisplayName("⚠️ une référence forgée ne change pas la route interrogée")
    void referenceForgee() {
        // Elle vient d'une notification que n'importe qui peut poster.
        serveur.expect(requestTo(ClientMoneyFusion.URL_STATUT_PAR_DEFAUT + "..%2Fadmin"))
                .andRespond(withSuccess("{\"statut\": false}", MediaType.APPLICATION_JSON));

        assertThat(client.statut("../admin")).isEmpty();
        serveur.verify();
    }

    @ParameterizedTest(name = "« {0} » → {1}")
    @CsvSource({
            "paid,      REUSSIE",
            "PAID,      REUSSIE",
            "failure,   ECHOUEE",
            "pending,   EN_COURS",
            // ⚠️ « no paid » n'est pas définitif d'après la documentation :
            //    le traiter en échec annulerait un client encore sur la page.
            "no paid,   EN_COURS",
            "inconnu,   EN_COURS"
    })
    @DisplayName("traduit le vocabulaire de MoneyFusion")
    void vocabulaire(String statut, Issue attendue) {
        assertThat(ClientMoneyFusion.issueDe(statut)).isEqualTo(attendue);
    }

    @ParameterizedTest(name = "« {0} » → {1}")
    @CsvSource({
            "237699000000,     699000000",
            "23670778815,      70778815",
            "+237 6 99 00 00 00, 699000000",
            "00237699000000,   699000000",
            "699000000,        699000000"
    })
    @DisplayName("envoie le numéro au format national")
    void numeroNational(String saisi, String attendu) {
        assertThat(ClientMoneyFusion.numeroNational(saisi)).isEqualTo(attendu);
    }

    @Test
    @DisplayName("non configuré : 503, et la boutique se dit en démonstration")
    void nonConfigure() {
        var vide = new ClientMoneyFusion("", "", "", "", RestClient.builder());

        assertThat(vide.estConfigure()).isFalse();
        assertThat(vide.estDemonstration()).isTrue();
        assertThatThrownBy(() -> vide.encaisser(demande()))
                .isInstanceOf(PasserellePaiement.OperateurIndisponible.class);
    }

    @Test
    @DisplayName("⚠️ un lien en http:// n'est pas un lien configuré")
    void lienEnClair() {
        var clair = new ClientMoneyFusion("http://pay.moneyfusion.net/x", "", "", "", RestClient.builder());
        assertThat(clair.estConfigure()).isFalse();
    }

    @Test
    @DisplayName("🎯 avec un relais, l'appel part vers le relais en CONNECT — le contenu reste chiffré")
    void passeParLeRelais() throws Exception {
        // Un faux relais : il note la première ligne reçue, puis raccroche.
        try (var faux = new java.net.ServerSocket(0)) {
            var premiereLigne = new java.util.concurrent.CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var s = faux.accept();
                     var lecteur = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()))) {
                    premiereLigne.complete(lecteur.readLine());
                } catch (Exception e) {
                    premiereLigne.completeExceptionally(e);
                }
            });

            var avecRelais = new ClientMoneyFusion(LIEN, "", "", "127.0.0.1:" + faux.getLocalPort(),
                    RestClient.builder());

            // Le relais raccroche : l'appel échoue, c'est attendu.
            assertThatThrownBy(() -> avecRelais.encaisser(demande()))
                    .isInstanceOf(PasserellePaiement.OperateurIndisponible.class);

            // ⚠️ CONNECT, et seulement l'hôte : le chemin — qui EST le secret —
            //    ne voyage que dans le tunnel chiffré, jamais en clair vers le
            //    relais.
            String ligne = premiereLigne.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(ligne).isEqualTo("CONNECT www.pay.moneyfusion.net:443 HTTP/1.1");
            assertThat(ligne).doesNotContain("abc123");
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"sans-port", "hote:", "hote:abc", "hote:70000", ":8888"})
    @DisplayName("⚠️ un relais mal écrit empêche de démarrer, plutôt que de passer sans lui")
    void relaisMalEcrit(String valeur) {
        assertThatThrownBy(() -> ClientMoneyFusion.relaisDe(valeur))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("vide : pas de relais")
    void pasDeRelais() {
        assertThat(ClientMoneyFusion.relaisDe("  ")).isNull();
    }
}
