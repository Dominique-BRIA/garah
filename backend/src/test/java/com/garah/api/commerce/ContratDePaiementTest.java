package com.garah.api.commerce;

import com.garah.api.commerce.domaine.ServicePaiementMobile.DemandePaiement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les NOMS des champs d'un paiement sont un contrat.
 *
 * <h2>🎯 Le défaut que ce test ferme</h2>
 *
 * <p>Le serveur annonçait {@code paiementId} et {@code reference} ; les deux
 * applications lisent {@code id} et {@code referenceTransaction}. Le paiement
 * partait donc correctement — l'opérateur poussait bien sa demande de code sur
 * le téléphone — et l'écran <b>échouait au retour</b>.</p>
 *
 * <p>⚠️ Le symptôme trompe autant qu'il est possible : « une erreur
 * inattendue » <b>après</b> que le téléphone a sonné. On cherche du côté de
 * l'opérateur, qui a parfaitement fait son travail.</p>
 *
 * <p>Sur mobile, le cast d'un {@code null} levait une {@code TypeError}. Sur le
 * web, {@code p.id} valait {@code undefined} et la vérification appelait
 * {@code /api/paiements/undefined/verification}. Deux symptômes différents,
 * une seule cause.</p>
 *
 * <h2>⚠️ Le troisième désaccord de cette famille</h2>
 *
 * <p>Après la connexion (D-36) et la liste des conversations, c'est le
 * troisième contrat écrit d'un côté et lu d'un autre sans que rien ne les
 * confronte. Aucune des deux applications ne vérifie quoi que ce soit à
 * l'exécution : en TypeScript {@code post<T>} est une promesse faite au
 * compilateur, en Dart lire une clé absente rend {@code null}.</p>
 *
 * <p>Ce test fige les noms <b>là où ils se décident</b>. Renommer un champ
 * casse ici, dans le dépôt qui l'a renommé — pas trois semaines plus tard,
 * devant un client dont le téléphone vient de sonner.</p>
 */
@DisplayName("Contrat de la réponse de paiement")
class ContratDePaiementTest {

    private static List<String> champsDe(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    @Test
    @DisplayName("⚠️ les noms sont figés : deux applications en dépendent")
    void lesNomsSontFiges() {
        assertThat(champsDe(DemandePaiement.class))
                .as("""
                        Les écrans de paiement lisent EXACTEMENT ces noms. \
                        En changer un casse le paiement APRÈS que l'opérateur \
                        a poussé sa demande — au pire moment, quand le client \
                        a déjà son téléphone en main.""")
                .containsExactlyInAnyOrder(
                        "id", "statut", "montant", "moyen",
                        "referenceTransaction", "codeUssd");
    }

    @Test
    @DisplayName("⚠️ les anciens noms ne reviennent pas")
    void lesAnciensNomsNeReviennentPas() {
        // `paiementId` et `reference` sont ceux qui ont cassé. `operateur`
        // portait DEUX sens selon la route — l'opérateur annoncé par Campay à
        // la demande, notre moyen de paiement à la relecture — donc un champ
        // sur lequel on ne pouvait rien construire.
        assertThat(champsDe(DemandePaiement.class))
                .doesNotContain("paiementId", "reference", "operateur");
    }
}
