package com.garah.api.iam;

import com.garah.api.iam.domaine.*;
import com.garah.api.iam.infra.IdentiteSocialeRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * « Continuer avec Google ».
 *
 * <p><b>Le vérificateur est remplacé par une doublure</b>, et c'est délibéré :
 * la vérification réelle interroge Google pour obtenir ses clés publiques. Un
 * test qui en dépendrait deviendrait <i>intermittent</i> — il passerait chez
 * moi et échouerait en CI un jour de réseau lent. Le chapitre 20 range ce type
 * d'échec au-dessus de tous les autres en nuisance.</p>
 *
 * <p>Ce qui est testé ici, c'est donc la <b>décision</b> prise à partir d'une
 * identité vérifiée : créer, retrouver, rattacher ou refuser. La vérification
 * elle-même relève de {@code VerificateurOidc}, et son contrôle le plus
 * important — le destinataire du jeton — n'a pas besoin du réseau.</p>
 */
@SpringBootTest
@DisplayName("Connexion sociale")
class ConnexionSocialeTest {

    private static final String EMAIL = "social.test@garah.cm";
    private static final String SUJET = "115742260538000111222";

    @Autowired ServiceConnexionSociale connexion;
    @Autowired ServiceAuthentification authentification;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired IdentiteSocialeRepository identites;
    @Autowired PasswordEncoder encodeur;
    @Autowired JdbcTemplate sql;

    @MockitoBean VerificateurIdentiteSociale verificateur;

    /**
     * ⚠️ <b>L'ordre compte, et ma première version l'avait faux.</b>
     *
     * <p>Elle commençait par {@code DELETE FROM identite_sociale}, par réflexe
     * — on supprime l'enfant avant le parent. Les sept tests ont échoué :</p>
     *
     * <pre>Le compte 2 n aurait plus aucun moyen de connexion :
     *     ni mot de passe, ni identite sociale.</pre>
     *
     * <p>Le trigger de V38 faisait exactement son travail : retirer la seule
     * identité d'un compte sans mot de passe l'enferme dehors. <b>Ce n'est pas
     * le test qui a trouvé un défaut, c'est la base qui a trouvé un défaut
     * dans le test</b> — comme la clé étrangère qui avait cassé vingt-deux
     * nettoyages au chapitre 17.</p>
     *
     * <p>La bonne manière est de supprimer <b>l'utilisateur</b> : le
     * {@code ON DELETE CASCADE} emporte ses identités, et le trigger constate
     * que le compte n'existe plus — il n'y a alors plus personne à enfermer
     * dehors.</p>
     */
    @BeforeEach
    void nettoyer() {
        sql.update("DELETE FROM evenement_securite WHERE utilisateur_id IN "
                + "(SELECT id FROM utilisateur WHERE lower(email) LIKE 'social.test%')");
        sql.update("DELETE FROM client WHERE id IN "
                + "(SELECT id FROM utilisateur WHERE lower(email) LIKE 'social.test%')");
        // Emporte identite_sociale par cascade — voir ci-dessus.
        sql.update("DELETE FROM utilisateur WHERE lower(email) LIKE 'social.test%'");
    }

    /** Programme la doublure pour qu'elle renvoie l'identité voulue. */
    private void googleRenvoie(String sujet, String email, boolean verifie) {
        when(verificateur.verifier(any(), anyString())).thenReturn(
                new IdentiteVerifiee(FournisseurIdentite.GOOGLE, sujet, email, verifie, "Aline Ngo"));
    }

    // -------------------------------------------------------------------------
    // Cas 3 : personne inconnue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un visiteur inconnu obtient un compte client complet")
    void creeLeCompte() {
        googleRenvoie(SUJET, EMAIL, true);

        ResultatConnexion session = connexion.connecter(
                FournisseurIdentite.GOOGLE, "jeton-peu-importe", "10.0.0.1");

        assertThat(session.type()).isEqualTo(TypeUtilisateur.CLIENT);

        Utilisateur cree = utilisateurs.findByEmailIgnoreCase(EMAIL).orElseThrow();

        // ⚠️ La ligne `client` DOIT exister. Un utilisateur sans elle se
        // connecte normalement puis échoue au premier ajout au panier — et le
        // défaut ne se verrait qu'au moment de payer.
        Integer lignesClient = sql.queryForObject(
                "SELECT count(*) FROM client WHERE id = ?", Integer.class, cree.getId());
        assertThat(lignesClient).isEqualTo(1);

        // 🎯 D-23 honoré sans le moindre courriel : Google vient d'attester
        //    que cette personne contrôle cette boîte.
        assertThat(cree.estEmailVerifie()).isTrue();

        // Aucun mot de passe — c'est ce que V38 a rendu possible.
        assertThat(cree.getMotDePasse()).isNull();
    }

    @Test
    @DisplayName("la base refuse un compte sans AUCUN moyen de connexion")
    void unCompteSansMoyenDeConnexionEstRefuse() {
        googleRenvoie(SUJET, EMAIL, true);
        connexion.connecter(FournisseurIdentite.GOOGLE, "jeton", "10.0.0.1");

        Long id = utilisateurs.findByEmailIgnoreCase(EMAIL).orElseThrow().getId();

        // On retire la seule identité : le compte n'aurait plus aucun moyen
        // d'entrer. Le trigger de V38 refuse AU COMMIT.
        assertThatThrownBy(() ->
                sql.update("DELETE FROM identite_sociale WHERE utilisateur_id = ?", id))
                .hasMessageContaining("moyen de connexion");
    }

    // -------------------------------------------------------------------------
    // Cas 1 : identité déjà connue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("revenir ne crée pas un second compte")
    void deuxiemeConnexionReutiliseLeCompte() {
        googleRenvoie(SUJET, EMAIL, true);

        Long premier = connexion.connecter(FournisseurIdentite.GOOGLE, "j1", "10.0.0.1").utilisateurId();
        Long second = connexion.connecter(FournisseurIdentite.GOOGLE, "j2", "10.0.0.1").utilisateurId();

        assertThat(second).isEqualTo(premier);
        assertThat(identites.findAll().stream()
                .filter(i -> i.getSujet().equals(SUJET)).count()).isEqualTo(1);
    }

    /**
     * Le sujet prime sur l'adresse, et c'est le cœur du modèle.
     *
     * <p>Une adresse Google Workspace peut être réattribuée : quelqu'un part,
     * un homonyme arrive. Si l'identification se faisait par l'adresse, le
     * nouvel arrivant récupérerait les commandes du précédent.</p>
     */
    @Test
    @DisplayName("le compte suit le sujet, même si l'adresse a changé")
    void leSujetPrimeSurLAdresse() {
        googleRenvoie(SUJET, EMAIL, true);
        Long premier = connexion.connecter(FournisseurIdentite.GOOGLE, "j1", "10.0.0.1").utilisateurId();

        // Même personne, nouvelle adresse annoncée par Google.
        googleRenvoie(SUJET, "social.test.nouvelle@garah.cm", true);
        Long second = connexion.connecter(FournisseurIdentite.GOOGLE, "j2", "10.0.0.1").utilisateurId();

        assertThat(second).isEqualTo(premier);
    }

    // -------------------------------------------------------------------------
    // Cas 2 : rattachement à un compte existant
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("une adresse ATTESTÉE rattache le compte existant")
    void rattacheQuandGoogleAtteste() {
        Long existant = creerUnCompteAvecMotDePasse();

        googleRenvoie(SUJET, EMAIL, true);
        Long obtenu = connexion.connecter(FournisseurIdentite.GOOGLE, "j", "10.0.0.1").utilisateurId();

        assertThat(obtenu).isEqualTo(existant);

        // Le mot de passe SURVIT : les deux moyens coexistent désormais.
        assertThat(utilisateurs.findById(existant).orElseThrow().getMotDePasse()).isNotNull();

        // Et le rattachement laisse une trace lisible. Volontairement son
        // PROPRE type d'événement : le compter comme un changement de mot de
        // passe ferait mentir le score de risque (chapitre 18).
        Integer traces = sql.queryForObject("""
                SELECT count(*) FROM evenement_securite
                 WHERE utilisateur_id = ? AND type = 'RATTACHEMENT_SOCIAL'
                """, Integer.class, existant);
        assertThat(traces).isEqualTo(1);
    }

    /**
     * ⚠️ Le test le plus important du fichier.
     *
     * <p>Sans ce refus, il suffirait d'ouvrir un compte chez un fournisseur
     * laxiste en déclarant l'adresse d'un client GARAH pour prendre son
     * compte, ses commandes et son historique de paiement.</p>
     */
    @Test
    @DisplayName("une adresse NON attestée ne rattache rien")
    void refuseQuandLAdresseNEstPasAttestee() {
        Long existant = creerUnCompteAvecMotDePasse();

        googleRenvoie(SUJET, EMAIL, false);   // email_verified = false

        assertThatThrownBy(() ->
                connexion.connecter(FournisseurIdentite.GOOGLE, "j", "10.0.0.1"))
                .isInstanceOf(ServiceConnexionSociale.RattachementRefuse.class);

        assertThat(identites.existsByUtilisateurIdAndFournisseur(
                existant, FournisseurIdentite.GOOGLE)).isFalse();
    }

    /**
     * D-53 : sans adresse, le compte se crée quand même.
     *
     * <p>Auparavant on levait {@code AdresseIndisponible}, ce qui rendait
     * TikTok inutilisable — il n'en fournit jamais — et Facebook aléatoire.</p>
     *
     * <p>⚠️ Le prix est réel et assumé : ce compte n'a <b>aucun canal hors de
     * l'application</b>. Si son colis arrive à Bangui et qu'il ne l'ouvre pas,
     * personne ne peut le prévenir.</p>
     */
    @Test
    @DisplayName("sans adresse, le compte se crée quand même (D-53)")
    void sansAdresseLeCompteSeCreeQuandMeme() {
        googleRenvoie(SUJET, null, false);

        Long id = connexion.connecter(FournisseurIdentite.GOOGLE, "j", "10.0.0.1")
                .utilisateurId();

        Utilisateur cree = utilisateurs.findById(id).orElseThrow();

        assertThat(cree.getEmail()).isNull();
        assertThat(cree.getMotDePasse()).isNull();
        assertThat(cree.estEmailVerifie())
                .as("aucune adresse ne peut etre dite verifiee")
                .isFalse();

        Integer lignesClient = sql.queryForObject(
                "SELECT count(*) FROM client WHERE id = ?", Integer.class, id);
        assertThat(lignesClient).isEqualTo(1);

        sql.update("DELETE FROM client WHERE id = ?", id);
        sql.update("DELETE FROM utilisateur WHERE id = ?", id);
    }

    /**
     * ⚠️ Une adresse que le fournisseur n'atteste pas ne doit PAS être marquée
     * vérifiée.
     *
     * <p>Meta ne dit pas s'il a vérifié l'adresse qu'il transmet. La marquer
     * vérifiée en ferait une preuve qu'elle n'est pas — et permettrait plus
     * tard d'y envoyer un code de retrait sans que personne n'ait jamais
     * confirmé la contrôler.</p>
     */
    @Test
    @DisplayName("une adresse non attestée n'est pas marquée vérifiée")
    void adresseNonAttesteeResteNonVerifiee() {
        when(verificateur.verifier(any(), anyString())).thenReturn(
                new IdentiteVerifiee(FournisseurIdentite.FACEBOOK, SUJET, EMAIL, true, "Aline"));

        Long id = connexion.connecter(FournisseurIdentite.FACEBOOK, "j", "10.0.0.1")
                .utilisateurId();

        assertThat(utilisateurs.findById(id).orElseThrow().estEmailVerifie())
                .as("Facebook n atteste pas : l adresse reste non verifiee")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // L'effet de bord sur la connexion par mot de passe
    // -------------------------------------------------------------------------

    /**
     * Un compte né par Google n'a pas de mot de passe.
     *
     * <p>Sans la garde ajoutée dans {@code connecter()},
     * {@code encodeur.matches(x, null)} lèverait et le client recevrait un
     * 500. On veut le refus normal — et surtout <b>le même</b> que pour une
     * adresse inconnue : répondre « ce compte utilise Google » transformerait
     * le formulaire en annuaire.</p>
     */
    @Test
    @DisplayName("le formulaire mot de passe refuse proprement un compte Google")
    void leFormulaireMotDePasseNeCassePasSurUnCompteSansMotDePasse() {
        googleRenvoie(SUJET, EMAIL, true);
        connexion.connecter(FournisseurIdentite.GOOGLE, "j", "10.0.0.1");

        assertThatThrownBy(() ->
                authentification.connecter(EMAIL, "n-importe-quoi", "10.0.0.1"))
                .isInstanceOf(ServiceAuthentification.IdentifiantsInvalides.class);
    }

    // -------------------------------------------------------------------------

    private Long creerUnCompteAvecMotDePasse() {
        Long id = sql.queryForObject("""
                INSERT INTO utilisateur (type, nom, email, mot_de_passe, email_verifie)
                VALUES ('CLIENT', 'Ngo Bassong', ?, ?, true)
                RETURNING id
                """, Long.class, EMAIL, encodeur.encode("un-mot-de-passe-long"));

        sql.update("INSERT INTO client (id, code_client) VALUES (?, ?)",
                id, "CLI-T%05d".formatted(id % 100000));

        return id;
    }
}
