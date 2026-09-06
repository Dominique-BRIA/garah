package com.garah.api.iam;

import com.garah.api.iam.domaine.*;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.surveillance.domaine.TypeEvenementSecurite;
import com.garah.api.surveillance.infra.EvenementSecuriteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La connexion, de bout en bout.
 *
 * <p><b>Pourquoi cette classe n'est PAS {@code @Transactional}</b>, contrairement
 * à {@code ServicePermissionsTest} : le service d'événements de sécurité écrit
 * en {@code REQUIRES_NEW}, donc dans sa propre transaction. Ces écritures sont
 * <b>committées même si la transaction appelante est annulée</b> — c'est
 * exactement le but : le journal de sécurité doit survivre à ce qu'il
 * journalise.</p>
 *
 * <p>Conséquence pour le test : un rollback ne nettoierait pas les événements.
 * On nettoie donc explicitement.</p>
 */
@SpringBootTest
@DisplayName("Connexion")
class ServiceAuthentificationTest {

    private static final String EMAIL = "auth.test@garah.cm";
    private static final String MOT_DE_PASSE = "MotDePasse!2026";

    @Autowired ServiceAuthentification authentification;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired EvenementSecuriteRepository evenements;
    @Autowired PasswordEncoder encodeur;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    private Utilisateur compte;

    /**
     * ⚠️ Un utilisateur de type {@code RESPONSABLE} DOIT avoir sa ligne dans
     * {@code responsable} : c'est l'invariant I-06, que SQL ne sait pas
     * exprimer (« au moins un »).
     *
     * <p>Ma première version du test créait l'utilisateur sans cette ligne. La
     * connexion échouait avec « Responsable introuvable » — une donnée
     * incohérente, pas un bug du service. Un rappel utile : les invariants que
     * la base ne porte pas, ce sont ceux qu'on oublie.</p>
     */
    @BeforeEach
    void creerLeCompte() {
        nettoyer();

        // Les deux enregistrements DOIVENT tenir dans la MÊME transaction.
        //
        // Sans ça, l'utilisateur revient « détaché » du premier save(), et
        // Hibernate refuse : « detached entity passed to persist ». C'est le
        // principe du chapitre 04 §7 vu depuis un test : ce qui doit être vrai
        // ensemble s'écrit ensemble.
        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.RESPONSABLE, "Ateba", EMAIL, encodeur.encode(MOT_DE_PASSE)));
            responsables.save(new Responsable(u, "RESP-AUTH-001"));
        });

        compte = utilisateurs.findByEmailIgnoreCase(EMAIL).orElseThrow();
    }

    @AfterEach
    void nettoyer() {
        utilisateurs.findByEmailIgnoreCase(EMAIL).ifPresent(u -> {
            evenements.deleteAll(evenements.findByUtilisateurIdOrderByDateHeureDesc(u.getId()));
            responsables.findById(u.getId()).ifPresent(responsables::delete);
            utilisateurs.delete(u);
        });
    }

    @Test
    @DisplayName("un mot de passe correct produit un jeton")
    void connexionReussie() {
        ResultatConnexion resultat = authentification.connecter(EMAIL, MOT_DE_PASSE, "41.202.0.1");

        assertThat(resultat.jeton()).isNotBlank();
        // Un JWT compact a exactement trois parties separees par des points.
        assertThat(resultat.jeton().split("\\.")).hasSize(3);
        assertThat(resultat.type()).isEqualTo(TypeUtilisateur.RESPONSABLE);
        assertThat(resultat.dureeSecondes()).isPositive();
    }

    @Test
    @DisplayName("le mot de passe n'est jamais stocké en clair")
    void leMotDePasseEstHache() {
        Utilisateur enBase = utilisateurs.findByEmailIgnoreCase(EMAIL).orElseThrow();

        assertThat(enBase.getMotDePasse())
                .isNotEqualTo(MOT_DE_PASSE)
                .startsWith("$2");                       // signature BCrypt
        assertThat(encodeur.matches(MOT_DE_PASSE, enBase.getMotDePasse())).isTrue();
    }

    @Test
    @DisplayName("un mot de passe faux est refusé et laisse une trace")
    void mauvaisMotDePasse() {
        assertThatThrownBy(() -> authentification.connecter(EMAIL, "faux", "41.202.0.1"))
                .isInstanceOf(ServiceAuthentification.IdentifiantsInvalides.class);

        assertThat(evenements.findByUtilisateurIdOrderByDateHeureDesc(compte.getId()))
                .extracting(e -> e.getType())
                .contains(TypeEvenementSecurite.ECHEC_CONNEXION);
    }

    @Test
    @DisplayName("une adresse inconnue donne exactement la même erreur")
    void adresseInconnue() {
        // Deux messages différents transformeraient le formulaire en annuaire.
        assertThatThrownBy(() -> authentification.connecter("personne@garah.cm", "x", "41.202.0.1"))
                .isInstanceOf(ServiceAuthentification.IdentifiantsInvalides.class)
                .hasMessageContaining("Adresse e-mail ou mot de passe incorrect");
    }

    @Test
    @DisplayName("un compte bloqué est refusé, même avec le bon mot de passe")
    void compteBloque() {
        compte.setStatut(StatutUtilisateur.BLOQUE);
        utilisateurs.save(compte);

        assertThatThrownBy(() -> authentification.connecter(EMAIL, MOT_DE_PASSE, "41.202.0.1"))
                .isInstanceOf(ServiceAuthentification.CompteBloque.class);
    }

    @Test
    @DisplayName("la casse de l'adresse n'empêche pas de se connecter")
    void adresseInsensibleALaCasse() {
        ResultatConnexion resultat =
                authentification.connecter(EMAIL.toUpperCase(), MOT_DE_PASSE, "41.202.0.1");

        assertThat(resultat.utilisateurId()).isEqualTo(compte.getId());
    }
}
