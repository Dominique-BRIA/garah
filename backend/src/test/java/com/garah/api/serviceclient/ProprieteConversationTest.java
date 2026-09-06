package com.garah.api.serviceclient;

import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.domaine.ServiceInscription;
import com.garah.api.serviceclient.domaine.ServiceConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un client ne touche qu'à <b>ses</b> conversations.
 *
 * <h2>La faille que ces tests ferment</h2>
 *
 * <p>Les routes de conversation n'exigent aucune permission — c'est normal :
 * un client n'en a aucune, son accès repose sur la <b>propriété</b> de ses
 * données (chapitre 08). Mais tant que personne ne vérifiait cette propriété,
 * « aucune permission exigée » signifiait « ouvert à tout compte connecté ».</p>
 *
 * <p>N'importe quel client pouvait, en changeant un identifiant dans l'URL :
 * lire la conversation d'un autre, y écrire, consulter ses prix négociés, et
 * <b>accepter ou refuser ses propositions</b>.</p>
 *
 * <p>Trouvé en auditant les routes avant déploiement, pas par un test — d'où
 * ce fichier, pour que la question ne se repose pas.</p>
 */
@SpringBootTest
@DisplayName("Propriete des conversations")
class ProprieteConversationTest {

    private static final String ALICE = "alice.conv@garah.cm";
    private static final String BOB = "bob.conv@garah.cm";

    @Autowired ServiceConversation conversations;
    @Autowired ServiceInscription inscription;
    @Autowired JdbcTemplate sql;

    private Long alice;
    private Long bob;
    private Long conversationDAlice;

    @BeforeEach
    void preparer() {
        for (String email : new String[]{ALICE, BOB}) {
            sql.update("""
                    DELETE FROM message WHERE conversation_id IN (
                        SELECT c.id FROM conversation c
                         WHERE c.client_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?))
                    """, email);
            sql.update("""
                    DELETE FROM conversation WHERE client_id IN (
                        SELECT id FROM utilisateur WHERE lower(email) = ?)
                    """, email);
            sql.update("DELETE FROM jeton_rafraichissement WHERE utilisateur_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM evenement_securite WHERE utilisateur_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM client WHERE id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", email);
            sql.update("DELETE FROM utilisateur WHERE lower(email) = ?", email);
        }

        alice = inscription.inscrire(ALICE, "un-mot-de-passe-long", "Alice",
                null, null, "fr", "10.0.0.1").utilisateurId();
        bob = inscription.inscrire(BOB, "un-mot-de-passe-long", "Bob",
                null, null, "fr", "10.0.0.2").utilisateurId();

        conversationDAlice = conversations.ouvrir(alice, "Ma commande",
                "Bonjour, où en est ma commande ?").getId();
    }

    @Test
    @DisplayName("Alice accede a sa propre conversation")
    void leProprietairePasse() {
        assertThatCode(() -> conversations.exigerAcces(conversationDAlice, alice, true))
                .doesNotThrowAnyException();
    }

    /**
     * 🎯 Le test central.
     *
     * <p>Bob est un client parfaitement légitime, connecté, avec un jeton
     * valide. Il ne doit pas pour autant voir la conversation d'Alice.</p>
     */
    @Test
    @DisplayName("Bob ne peut PAS acceder a la conversation d'Alice")
    void unAutreClientEstRefuse() {
        assertThatThrownBy(() -> conversations.exigerAcces(conversationDAlice, bob, true))
                .isInstanceOf(RessourceIntrouvable.class);
    }

    /**
     * ⚠️ « Introuvable », jamais « interdit ».
     *
     * <p>Un 403 confirmerait que la conversation existe. En parcourant les
     * identifiants, un curieux mesurerait l'activité du service client sans
     * jamais lire un seul message.</p>
     */
    @Test
    @DisplayName("le refus est indistinguable d'une conversation inexistante")
    void leRefusNeRenseignePas() {
        String surCelleDunAutre = messageDeRefus(conversationDAlice, bob);
        String surUneInexistante = messageDeRefus(999_999_999L, bob);

        assertThatThrownBy(() -> conversations.exigerAcces(999_999_999L, bob, true))
                .isInstanceOf(RessourceIntrouvable.class);

        // Meme TYPE d'erreur dans les deux cas. Le message contient
        // l'identifiant demande, donc il differe — mais rien n'y distingue
        // « elle existe et n'est pas a toi » de « elle n'existe pas ».
        assertThatCode(() -> {
            if (!surCelleDunAutre.replaceAll("[0-9]+", "N")
                    .equals(surUneInexistante.replaceAll("[0-9]+", "N"))) {
                throw new AssertionError("Les deux refus doivent avoir la meme forme : "
                        + surCelleDunAutre + " vs " + surUneInexistante);
            }
        }).doesNotThrowAnyException();
    }

    /**
     * Un responsable traite les conversations des autres — c'est son métier.
     *
     * <p>Ses droits à lui sont contrôlés par les {@code @PreAuthorize} des
     * routes qui lui sont réservées ({@code CONVERSATION_PRENDRE},
     * {@code CONVERSATION_FERMER}…), pas par la propriété.</p>
     */
    @Test
    @DisplayName("un responsable n'est pas soumis a la propriete")
    void leResponsablePasse() {
        assertThatCode(() -> conversations.exigerAcces(conversationDAlice, bob, false))
                .doesNotThrowAnyException();
    }

    private String messageDeRefus(Long conversationId, Long utilisateurId) {
        try {
            conversations.exigerAcces(conversationId, utilisateurId, true);
            throw new AssertionError("L'acces aurait du etre refuse.");
        } catch (RessourceIntrouvable e) {
            return e.getMessage();
        }
    }
}
