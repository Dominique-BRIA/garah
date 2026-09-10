package com.garah.api.commerce;

import com.garah.api.commerce.infra.ClientCampay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un refus de l'opérateur n'est pas une panne.
 *
 * <h2>🎯 Le défaut que ces tests ferment</h2>
 *
 * <p>Tout échec d'appel à Campay devenait « le service de paiement est
 * momentanément injoignable ». C'était faux la moitié du temps : un
 * {@code 400} signifie que l'opérateur a <b>répondu</b>, parfaitement, pour
 * dire <b>non</b>.</p>
 *
 * <p>Le cas rencontré : un paiement de moins de 20 FCFA. Campay impose un
 * minimum ; il refusait donc, et l'écran annonçait une indisponibilité. On
 * cherchait une panne réseau pendant que la réponse était sur la table.</p>
 *
 * <h2>⚠️ Deux natures, deux codes HTTP</h2>
 *
 * <pre>
 * injoignable   503   réessayer plus tard a du sens
 * refusé        422   réessayer à l'identique donnera le même refus
 * </pre>
 *
 * <p>Les confondre fait boucler le client sur un geste qui ne peut pas
 * réussir — et fait chercher le support du mauvais côté.</p>
 */
@DisplayName("Refus de l'opérateur")
class RefusOperateurTest {

    @Test
    @DisplayName("⚠️ un montant sous le minimum est REFUSÉ, pas « injoignable »")
    void sousLeMinimumEstUnRefus() {
        // 🎯 LE CAS RENCONTRÉ. Moins de 20 FCFA : Campay refuse, et l'écran
        //    disait « service injoignable ».
        ClientCampay client = new ClientCampay("", "", "", org.springframework.web.client.RestClient.builder());

        assertThatThrownBy(() ->
                client.encaisser(new BigDecimal("20"), "699707810", "Test", "CMD-1"))
                .isInstanceOf(ClientCampay.OperateurRefuse.class)
                .hasMessageContaining(String.valueOf(ClientCampay.MONTANT_MINIMUM));
    }

    @Test
    @DisplayName("⚠️ le refus rend 422, jamais 503")
    void leRefusRend422() {
        // 503 dirait « réessayez plus tard ». Or réessayer à l'identique
        // donnera exactement le même refus : le montant ne change pas tout
        // seul. L'écran doit dire quoi corriger, pas quoi attendre.
        var refus = new ClientCampay.OperateurRefuse("Montant trop faible.");

        assertThat(refus.getStatut()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("une panne, elle, reste un 503")
    void lIndisponibiliteRend503() {
        // ⚠️ La distinction ne vaut que si les DEUX restent justes : ramener
        //    toute erreur à un 422 déplacerait simplement le mensonge.
        var panne = new ClientCampay.OperateurIndisponible("Injoignable.");

        assertThat(panne.getStatut()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("le minimum est vérifié AVANT tout appel réseau")
    void leMinimumEstVerifieAvantLAppel() {
        // Le client est construit SANS configuration : s'il tentait un appel,
        // il lèverait « non configuré ». Obtenir un refus de montant prouve
        // donc que le contrôle passe en premier — la règle du projet, « dire
        // ce qui manque avant le clic ».
        ClientCampay client = new ClientCampay("", "", "", org.springframework.web.client.RestClient.builder());

        assertThatThrownBy(() ->
                client.encaisser(BigDecimal.ONE, "699707810", "Test", "CMD-2"))
                .isInstanceOf(ClientCampay.OperateurRefuse.class);
    }
}
