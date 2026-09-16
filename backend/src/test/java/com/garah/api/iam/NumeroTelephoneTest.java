package com.garah.api.iam;

import com.garah.api.iam.domaine.NumeroTelephone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un test unitaire pur : aucune base, aucun contexte Spring.
 *
 * <p>🎯 <b>Et le plus déterminant de la connexion WhatsApp.</b> Le numéro
 * normalisé est la clé d'identité d'un compte — s'il n'est pas déterministe,
 * la même personne obtient plusieurs comptes et ses commandes se répartissent
 * entre eux. Le défaut ne se voit pas à la connexion, qui réussit : il se voit
 * quand l'historique est vide.</p>
 */
@DisplayName("Normalisation d'un numéro")
class NumeroTelephoneTest {

    /**
     * ⚠️ Toutes ces écritures désignent la MÊME personne.
     *
     * <p>C'est la propriété centrale : quelqu'un qui tape son numéro avec des
     * espaces un jour et sans espaces le lendemain doit retrouver son compte.</p>
     */
    @Test
    @DisplayName("toutes les écritures d'un même numéro donnent la même chaîne")
    void memeNumeroMemeChaine() {
        String attendu = "+237699000000";

        for (String saisi : new String[]{
                "699000000",
                "699 00 00 00",
                "+237699000000",
                "+237 699 00 00 00",
                "237699000000",
                "00237699000000",
                "(237) 699-000-000",
                "  +237.699.000.000  "}) {

            assertThat(NumeroTelephone.normaliser(saisi))
                    .as("saisi = [%s]", saisi)
                    .isEqualTo(attendu);
        }
    }

    @Test
    @DisplayName("la Centrafrique est acceptée, avec ses huit chiffres")
    void centrafrique() {
        assertThat(NumeroTelephone.normaliser("+236 75 00 00 00")).isEqualTo("+23675000000");
        assertThat(NumeroTelephone.normaliser("0023675000000")).isEqualTo("+23675000000");
    }

    /**
     * ⚠️ Le piège que ce test ferme.
     *
     * <p>Un numéro centrafricain fait huit chiffres. Si on supposait
     * l'indicatif camerounais devant un numéro nu, on fabriquerait un numéro
     * <b>valide appartenant à quelqu'un d'autre</b> — et le code de connexion
     * partirait chez cette personne.</p>
     *
     * <p>On refuse donc, et l'interface demande l'indicatif.</p>
     */
    @Test
    @DisplayName("un numéro nu de huit chiffres est REFUSÉ, jamais deviné")
    void pasDIndicatifDevine() {
        assertThatThrownBy(() -> NumeroTelephone.normaliser("75000000"))
                .isInstanceOf(NumeroTelephone.NumeroInvalide.class);
    }

    @Test
    @DisplayName("les pays non desservis sont refusés")
    void paysNonDesservis() {
        for (String etranger : new String[]{
                "+33612345678",     // France
                "+1 202 555 0143",  // États-Unis
                "+221771234567"}) { // Sénégal

            assertThatThrownBy(() -> NumeroTelephone.normaliser(etranger))
                    .as("etranger = [%s]", etranger)
                    .isInstanceOf(NumeroTelephone.NumeroInvalide.class);
        }
    }

    @Test
    @DisplayName("une longueur fausse est refusée, même avec le bon indicatif")
    void longueurFausse() {
        for (String faux : new String[]{"+23769900", "+2376990000000", "+2367500", "", "   ", "abc"}) {
            assertThatThrownBy(() -> NumeroTelephone.normaliser(faux))
                    .as("faux = [%s]", faux)
                    .isInstanceOf(NumeroTelephone.NumeroInvalide.class);
        }

        assertThatThrownBy(() -> NumeroTelephone.normaliser(null))
                .isInstanceOf(NumeroTelephone.NumeroInvalide.class);
    }

    /**
     * Le masque confirme <b>où part le code</b> sans révéler le numéro.
     *
     * <p>Sur un téléphone partagé, ou une capture d'écran envoyée au support,
     * le numéro entier serait divulgué à qui regarde — alors que les deux
     * derniers chiffres suffisent à reconnaître le sien.</p>
     */
    @Test
    @DisplayName("le masque garde l'indicatif et les deux derniers chiffres")
    void masque() {
        String masque = NumeroTelephone.masquer("+237699000042");

        assertThat(masque).startsWith("+2376").endsWith("42");
        assertThat(masque).doesNotContain("99000");
    }
}
