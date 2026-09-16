package com.garah.api.iam;

import com.garah.api.iam.domaine.FournisseurIdentite;
import com.garah.api.iam.domaine.IdentiteVerifiee;
import com.garah.api.iam.domaine.VerificateurIdentiteSociale;
import com.garah.api.iam.domaine.VerificateurParFournisseur;
import com.garah.api.iam.infra.VerificateurSocial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'aiguillage entre fournisseurs — sans base ni contexte Spring.
 *
 * <p>Ce qui est vérifié ici, c'est le <b>comportement de bord</b> : un
 * fournisseur absent, un fournisseur éteint, une preuve vide. Les vérifications
 * réelles, elles, appellent Google, Meta ou TikTok et n'ont pas leur place dans
 * une suite qui doit rester rapide et sûre.</p>
 */
@DisplayName("Aiguillage des connexions sociales")
class AiguillageSocialTest {

    /** Un vérificateur de façade, dont on choisit le fournisseur et l'état. */
    private static VerificateurParFournisseur faux(FournisseurIdentite f, boolean actif) {
        return new VerificateurParFournisseur() {
            @Override public FournisseurIdentite fournisseur() { return f; }
            @Override public boolean estActif() { return actif; }
            @Override public IdentiteVerifiee verifier(String preuve) {
                return new IdentiteVerifiee(f, "sujet-" + f, "x@garah.cm", true, "Essai");
            }
        };
    }

    @Test
    @DisplayName("chaque fournisseur est aiguillé vers SON vérificateur")
    void aiguillage() {
        var aiguillage = new VerificateurSocial(List.of(
                faux(FournisseurIdentite.GOOGLE, true),
                faux(FournisseurIdentite.FACEBOOK, true),
                faux(FournisseurIdentite.TIKTOK, true)));

        for (var f : List.of(FournisseurIdentite.GOOGLE,
                FournisseurIdentite.FACEBOOK, FournisseurIdentite.TIKTOK)) {

            assertThat(aiguillage.verifier(f, "une-preuve").sujet()).isEqualTo("sujet-" + f);
        }
    }

    /**
     * ⚠️ Un fournisseur éteint répond <b>exactement comme</b> une preuve fausse.
     *
     * <p>Distinguer les deux — « ce fournisseur n'est pas activé » — apprendrait
     * à un inconnu quelle partie de notre configuration est incomplète. C'est
     * peu, et c'est déjà trop : on ne renseigne personne sur ses propres
     * manques.</p>
     */
    @Test
    @DisplayName("un fournisseur éteint refuse, sans dire qu'il est éteint")
    void fournisseurEteint() {
        var aiguillage = new VerificateurSocial(List.of(
                faux(FournisseurIdentite.GOOGLE, true),
                faux(FournisseurIdentite.FACEBOOK, false)));

        assertThatThrownBy(() -> aiguillage.verifier(FournisseurIdentite.FACEBOOK, "preuve"))
                .isInstanceOf(VerificateurIdentiteSociale.JetonSocialInvalide.class);

        assertThatThrownBy(() -> aiguillage.verifier(FournisseurIdentite.GOOGLE, "  "))
                .as("une preuve vide donne la MEME erreur")
                .isInstanceOf(VerificateurIdentiteSociale.JetonSocialInvalide.class);
    }

    @Test
    @DisplayName("un fournisseur sans vérificateur refuse aussi")
    void fournisseurAbsent() {
        var aiguillage = new VerificateurSocial(List.of(faux(FournisseurIdentite.GOOGLE, true)));

        assertThatThrownBy(() -> aiguillage.verifier(FournisseurIdentite.TIKTOK, "preuve"))
                .isInstanceOf(VerificateurIdentiteSociale.JetonSocialInvalide.class);
    }

    @Test
    @DisplayName("la liste des fournisseurs actifs n'annonce que ce qui marche")
    void listeDesActifs() {
        var aiguillage = new VerificateurSocial(List.of(
                faux(FournisseurIdentite.GOOGLE, true),
                faux(FournisseurIdentite.FACEBOOK, false),
                faux(FournisseurIdentite.TIKTOK, true)));

        assertThat(aiguillage.actifs()).containsExactly("GOOGLE", "TIKTOK");
    }

    /**
     * 🎯 La propriété qui compte le plus dans l'énumération.
     *
     * <p>Seul Google atteste l'adresse qu'il transmet. Facebook ne dit pas s'il
     * l'a vérifiée, et TikTok n'en donne aucune. Aucun des deux ne doit donc
     * pouvoir rattacher un compte GARAH existant : il suffirait sinon de
     * déclarer l'adresse de sa victime chez le fournisseur le plus laxiste pour
     * prendre son compte ici.</p>
     */
    @Test
    @DisplayName("Google seul peut rattacher un compte existant")
    void seulGoogleAtteste() {
        assertThat(FournisseurIdentite.GOOGLE.fournitUnEmailVerifie()).isTrue();

        assertThat(FournisseurIdentite.FACEBOOK.fournitUnEmailVerifie()).isFalse();
        assertThat(FournisseurIdentite.TIKTOK.fournitUnEmailVerifie()).isFalse();
        assertThat(FournisseurIdentite.WHATSAPP.fournitUnEmailVerifie()).isFalse();
    }
}
