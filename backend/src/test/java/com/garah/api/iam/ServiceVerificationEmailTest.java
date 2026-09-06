package com.garah.api.iam;

import com.garah.api.commerce.domaine.ServiceCommande;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.ServiceInscription;
import com.garah.api.iam.domaine.ServiceVerificationEmail;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La confirmation de l'adresse e-mail (D-23).
 *
 * <p>Ces tests verrouillent une règle <b>métier</b> autant que de sécurité :
 * le suivi de commande, le code de retrait et les avis d'acheminement partent
 * tous à cette adresse (D-07). Une adresse fausse, et la marchandise arrive à
 * Bangui sans que personne ne puisse être prévenu.</p>
 */
@SpringBootTest
@DisplayName("Confirmation de l'adresse e-mail")
class ServiceVerificationEmailTest {

    private static final String EMAIL = "verif.test@garah.cm";
    private static final String AUTRE = "verif.autre@garah.cm";
    private static final String MOT_DE_PASSE = "un-mot-de-passe-long";
    private static final String IP = "10.0.0.7";

    @Autowired ServiceVerificationEmail verification;
    @Autowired ServiceInscription inscription;
    @Autowired ServiceCommande commandes;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired JdbcTemplate sql;

    private Long utilisateurId;

    @BeforeEach
    void preparer() {
        for (String email : new String[]{EMAIL, AUTRE}) {
            sql.update("DELETE FROM jeton_verification_email WHERE utilisateur_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM jeton_rafraichissement WHERE utilisateur_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM evenement_securite WHERE utilisateur_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM panier WHERE client_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM client WHERE id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM utilisateur WHERE lower(email) = ?", email);
        }
        utilisateurId = inscription.inscrire(EMAIL, MOT_DE_PASSE, "Verif",
                null, null, "fr", IP).utilisateurId();
    }

    // -------------------------------------------------------------------------

    /** 🎯 Un compte fraîchement inscrit n'est PAS vérifié. */
    @Test
    @DisplayName("l'inscription cree un compte NON verifie")
    void lInscriptionNeVerifiePas() {
        assertThat(verification.estConfirme(utilisateurId)).isFalse();
    }

    @Test
    @DisplayName("l'inscription emet un jeton, stocke en empreinte")
    void unJetonEstEmisEtHache() {
        String jeton = verification.emettre(utilisateurId, EMAIL);

        // La valeur en clair ne doit apparaitre NULLE PART en base : cette
        // table permettrait sinon de verifier l'adresse de n'importe qui.
        Integer enClair = sql.queryForObject(
                "SELECT count(*) FROM jeton_verification_email WHERE empreinte = ?",
                Integer.class, jeton);
        assertThat(enClair).as("le jeton en clair ne doit jamais etre stocke").isZero();

        // Mais une empreinte de 64 caracteres, elle, existe bien.
        Integer empreintes = sql.queryForObject("""
                SELECT count(*) FROM jeton_verification_email
                 WHERE utilisateur_id = ? AND date_utilisation IS NULL
                   AND length(empreinte) = 64
                """, Integer.class, utilisateurId);
        assertThat(empreintes).isEqualTo(1);

        // Et il fonctionne : c'est la preuve que l'empreinte correspond.
        assertThatCode(() -> verification.confirmer(jeton)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un jeton valide confirme l'adresse")
    void unJetonValideConfirme() {
        String jeton = verification.emettre(utilisateurId, EMAIL);

        verification.confirmer(jeton);

        assertThat(verification.estConfirme(utilisateurId)).isTrue();
    }

    /** Un jeton ne sert qu'une fois. */
    @Test
    @DisplayName("un jeton deja utilise est refuse")
    void unJetonNeSertQuUneFois() {
        String jeton = verification.emettre(utilisateurId, EMAIL);
        verification.confirmer(jeton);

        assertThatThrownBy(() -> verification.confirmer(jeton))
                .isInstanceOf(ServiceVerificationEmail.LienInvalide.class);
    }

    /**
     * ⚠️ Emettre un nouveau lien invalide le precedent.
     *
     * <p>Sans cela, trois renvois laisseraient trois liens actifs — dont deux
     * dans des boîtes qu'on ne contrôle plus : un ancien e-mail transféré, une
     * capture d'écran partagée.</p>
     */
    @Test
    @DisplayName("un nouveau lien invalide l'ancien")
    void unNouveauLienInvalideLAncien() {
        String premier = verification.emettre(utilisateurId, EMAIL);
        String second = verification.emettre(utilisateurId, EMAIL);

        assertThatThrownBy(() -> verification.confirmer(premier))
                .isInstanceOf(ServiceVerificationEmail.LienInvalide.class);

        assertThatCode(() -> verification.confirmer(second)).doesNotThrowAnyException();
    }

    /**
     * 🎯 <b>Le test le moins évident, et le plus important.</b>
     *
     * <p>Je m'inscris avec mon adresse, je reçois le lien, je change mon e-mail
     * pour celui de quelqu'un d'autre, puis je clique. Sans le contrôle sur
     * l'adresse figée à l'émission, je viendrais de « confirmer » une adresse
     * que je ne contrôle pas.</p>
     */
    @Test
    @DisplayName("changer d'adresse entre l'envoi et le clic invalide le lien")
    void changerDAdresseInvalideLeLien() {
        String jeton = verification.emettre(utilisateurId, EMAIL);

        // L'utilisateur change son adresse APRES avoir recu le lien.
        sql.update("UPDATE utilisateur SET email = ? WHERE id = ?", AUTRE, utilisateurId);

        assertThatThrownBy(() -> verification.confirmer(jeton))
                .isInstanceOf(ServiceVerificationEmail.LienInvalide.class);

        assertThat(verification.estConfirme(utilisateurId)).isFalse();
    }

    @Test
    @DisplayName("un jeton inconnu, vide ou nul est refuse")
    void unJetonInconnuEstRefuse() {
        assertThatThrownBy(() -> verification.confirmer("jeton-invente"))
                .isInstanceOf(ServiceVerificationEmail.LienInvalide.class);
        assertThatThrownBy(() -> verification.confirmer(null))
                .isInstanceOf(ServiceVerificationEmail.LienInvalide.class);
        assertThatThrownBy(() -> verification.confirmer("  "))
                .isInstanceOf(ServiceVerificationEmail.LienInvalide.class);
    }

    // -------------------------------------------------------------------------
    // La barriere metier
    // -------------------------------------------------------------------------

    /**
     * 🎯 <b>La raison d'être de tout ce qui précède.</b>
     *
     * <p>Le panier est vide, donc {@code passer} pourrait échouer pour cette
     * raison. Ce test vérifie qu'il échoue <b>d'abord</b> sur l'adresse : la
     * barrière est posée avant tout le reste.</p>
     */
    @Test
    @DisplayName("commander sans adresse confirmee est refuse")
    void commanderExigeUneAdresseConfirmee() {
        assertThatThrownBy(() -> commandes.passer(utilisateurId, 1L, "fr"))
                .isInstanceOf(ServiceVerificationEmail.AdresseNonConfirmee.class);
    }

    @Test
    @DisplayName("une fois confirmee, la barriere tombe")
    void laBarriereTombeApresConfirmation() {
        verification.confirmer(verification.emettre(utilisateurId, EMAIL));

        // Le panier est vide : on attend PANIER_VIDE, plus AdresseNonConfirmee.
        // La distinction est le coeur du test — la barriere a bien ete franchie.
        assertThatThrownBy(() -> commandes.passer(utilisateurId, 1L, "fr"))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("panier");
    }

    // -------------------------------------------------------------------------
    // Les renvois
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("renvoyer a une adresse deja confirmee ne fait rien")
    void renvoyerSurUnCompteConfirmeEstSansEffet() {
        verification.confirmer(verification.emettre(utilisateurId, EMAIL));

        // Ni erreur ni e-mail : redemander un lien dont on n'avait pas besoin
        // n'est pas une faute, et repondre « deja confirmee » renseignerait
        // sur l'etat d'un compte.
        assertThatCode(() -> verification.renvoyer(utilisateurId)).doesNotThrowAnyException();
    }

    /**
     * Le plafond par COMPTE, distinct de celui par adresse IP.
     *
     * <p>Un attaquant qui change d'IP ne doit pas pouvoir faire pleuvoir des
     * e-mails sur une même victime — c'est du harcèlement par formulaire, et
     * ça abîme notre réputation d'expéditeur.</p>
     */
    @Test
    @DisplayName("trois renvois par heure, pas quatre")
    void lesRenvoisSontPlafonnesParCompte() {
        // L'inscription en a deja emis un ; deux renvois atteignent le plafond.
        verification.renvoyer(utilisateurId);
        verification.renvoyer(utilisateurId);

        assertThatThrownBy(() -> verification.renvoyer(utilisateurId))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("Trop de demandes");
    }
}
