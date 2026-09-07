package com.garah.api.serviceclient;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.*;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.serviceclient.domaine.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les conversations, y compris la course à la prise.
 *
 * <p>Non {@code @Transactional} : le test de concurrence a besoin de vraies
 * transactions concurrentes.</p>
 */
@SpringBootTest
@DisplayName("Conversations")
class ServiceConversationTest {

    private static final String EMAIL_CLIENT = "client.conv@garah.cm";
    private static final String PREFIXE_RESP = "resp.conv";

    @Autowired ServiceConversation conversations;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired ResponsableRepository responsables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private Long clientId;
    private List<Long> responsableIds;

    @BeforeEach
    void preparer() {
        nettoyer();

        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.CLIENT, "Essomba", EMAIL_CLIENT, "x"));
            u.marquerEmailVerifie();
            clientId = clients.save(new Client(u, "CLI-CONV-1")).getId();

            responsableIds = new java.util.ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                Utilisateur r = utilisateurs.save(new Utilisateur(
                        TypeUtilisateur.RESPONSABLE, "Resp " + i,
                        PREFIXE_RESP + i + "@garah.cm", "x"));
                r.marquerEmailVerifie();
                responsableIds.add(responsables.save(new Responsable(r, "M-CONV-" + i)).getId());
            }
        });
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM evaluation_conversation WHERE conversation_id IN (SELECT c.id FROM conversation c JOIN client cl ON cl.id = c.client_id WHERE cl.code_client = 'CLI-CONV-1')");
        jdbc.update("DELETE FROM proposition_prix WHERE conversation_id IN (SELECT c.id FROM conversation c JOIN client cl ON cl.id = c.client_id WHERE cl.code_client = 'CLI-CONV-1')");
        jdbc.update("DELETE FROM affectation_conversation WHERE conversation_id IN (SELECT c.id FROM conversation c JOIN client cl ON cl.id = c.client_id WHERE cl.code_client = 'CLI-CONV-1')");
        jdbc.update("DELETE FROM message WHERE conversation_id IN (SELECT c.id FROM conversation c JOIN client cl ON cl.id = c.client_id WHERE cl.code_client = 'CLI-CONV-1')");
        jdbc.update("DELETE FROM conversation WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-CONV-1')");
        jdbc.update("DELETE FROM client WHERE code_client = 'CLI-CONV-1'");
        jdbc.update("DELETE FROM responsable WHERE matricule LIKE 'M-CONV-%'");
        jdbc.update("DELETE FROM utilisateur WHERE email = ? OR email LIKE ?", EMAIL_CLIENT, PREFIXE_RESP + "%");
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("la liste nomme le client et compte ce qui n'est pas lu")
    void listeDuBackOffice() {
        Long convId = conversations.ouvrir(clientId, "Question prix", "Bonjour").getId();
        conversations.repondre(convId, clientId, "Vous êtes là ?");

        // Ce test exécute réellement les trois requêtes de la liste — dont
        // l'agrégat sur les messages. Une @Query cassée n'échouerait qu'au
        // moment où on l'appelle.
        ResumeConversation vue = conversations
                .administration(null, null, PageRequest.of(0, 25))
                .getContent().stream()
                .filter(c -> c.id().equals(convId))
                .findFirst()
                .orElseThrow();

        // « clientId 42 » n'apprend rien à l'agent qui parcourt la file.
        assertThat(vue.clientNom()).isNotBlank();

        // 🎯 Le chiffre qui compte est celui des NON LUS : c'est lui qui
        //    répond à « laquelle attend ma réponse ? ».
        assertThat(vue.nombreMessages()).isEqualTo(2);
        assertThat(vue.nonLus()).isEqualTo(2);
        assertThat(vue.dernierMessageLe()).isNotNull();

        // Le filtre par responsable : sans lui, un agent parcourrait les
        // dossiers de toute l'équipe pour retrouver les siens.
        Long responsableId = responsableIds.getFirst();
        assertThat(conversations.administration(null, responsableId, PageRequest.of(0, 25)))
                .isEmpty();

        conversations.prendre(convId, responsableId);

        assertThat(conversations.administration(null, responsableId, PageRequest.of(0, 25))
                .getContent().stream().map(ResumeConversation::id))
                .contains(convId);

        // Prise, elle sort de la file d'attente.
        assertThat(conversations.administration(StatutConversation.WAITING, null,
                PageRequest.of(0, 25)).getContent().stream().map(ResumeConversation::id))
                .doesNotContain(convId);
    }

    @Test
    @DisplayName("une conversation sans message ne casse pas la liste")
    void listeSansMessage() {
        // Une conversation peut exister sans message : le sujet suffit à
        // l'ouvrir. L'agrégat ne renvoie alors AUCUNE ligne pour elle, et
        // c'est le cas que la jointure naïve fait disparaître de la liste.
        Long convId = conversations.ouvrir(clientId, "Sujet seul", null).getId();

        ResumeConversation vue = conversations
                .administration(null, null, PageRequest.of(0, 25))
                .getContent().stream()
                .filter(c -> c.id().equals(convId))
                .findFirst()
                .orElseThrow();

        assertThat(vue.nombreMessages()).isZero();
        assertThat(vue.nonLus()).isZero();
        assertThat(vue.dernierMessageLe()).isNull();
    }

    @Test
    @DisplayName("une conversation naît en attente, sans responsable")
    void ouverture() {
        Conversation conv = conversations.ouvrir(clientId, "Question prix", "Bonjour, quel est votre meilleur prix ?");

        assertThat(conv.getStatut()).isEqualTo(StatutConversation.WAITING);
        assertThat(conv.getResponsableId()).isNull();
        assertThat(conversations.fileDAttente()).extracting(Conversation::getId).contains(conv.getId());
    }

    @Test
    @DisplayName("prendre une conversation l'attribue et la sort de la file")
    void prise() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();

        Conversation prise = conversations.prendre(convId, responsableIds.getFirst());

        assertThat(prise.getStatut()).isEqualTo(StatutConversation.ASSIGNED);
        assertThat(prise.getResponsableId()).isEqualTo(responsableIds.getFirst());
        assertThat(conversations.historique(convId)).hasSize(1);
    }

    /**
     * Le test de concurrence du chapitre : cinq responsables voient la même
     * conversation dans leur file et cliquent en même temps.
     */
    @Test
    @DisplayName("cinq responsables cliquent : un seul obtient la conversation")
    void courseALaPrise() throws Exception {
        Long convId = conversations.ouvrir(clientId, "Très demandée", "Bonjour").getId();

        AtomicInteger gagnants = new AtomicInteger();
        AtomicInteger perdants = new AtomicInteger();
        CyclicBarrier depart = new CyclicBarrier(5);
        CountDownLatch fini = new CountDownLatch(5);

        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (Long responsableId : responsableIds) {
                pool.submit(() -> {
                    try {
                        depart.await(10, TimeUnit.SECONDS);
                        conversations.prendre(convId, responsableId);
                        gagnants.incrementAndGet();
                    } catch (ConflitEtat attendu) {
                        perdants.incrementAndGet();
                    } catch (Exception inattendue) {
                        throw new IllegalStateException(inattendue);
                    } finally {
                        fini.countDown();
                    }
                });
            }
            assertThat(fini.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(gagnants.get()).isEqualTo(1);
        assertThat(perdants.get()).isEqualTo(4);

        // Une seule affectation ouverte : l'index unique partiel I-30 l'exige,
        // et l'UPDATE conditionnel l'a garanti sans verrou.
        Long affectations = jdbc.queryForObject(
                "SELECT count(*) FROM affectation_conversation WHERE conversation_id = ?",
                Long.class, convId);
        assertThat(affectations).isEqualTo(1);
    }

    @Test
    @DisplayName("prendre une conversation déjà prise donne un message compréhensible")
    void dejaPrise() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.get(0));

        assertThatThrownBy(() -> conversations.prendre(convId, responsableIds.get(1)))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("déjà pris");
    }

    @Test
    @DisplayName("un Admin peut réaffecter, mais doit se justifier")
    void reaffectation() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.get(0));

        assertThatThrownBy(() -> conversations.reaffecter(convId, responsableIds.get(1), responsableIds.get(2), "  "))
                .isInstanceOf(RegleMetierViolee.class);

        Conversation apres = conversations.reaffecter(convId, responsableIds.get(1), responsableIds.get(2),
                "Absence prolongée du responsable");

        assertThat(apres.getResponsableId()).isEqualTo(responsableIds.get(1));
        // Les deux affectations sont conservées : qui l'a eue, quand, pourquoi.
        assertThat(conversations.historique(convId)).hasSize(2);
    }

    @Test
    @DisplayName("on ne répond plus dans une conversation fermée")
    void pasDeReponseApresFermeture() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.getFirst());
        conversations.fermer(convId);

        assertThatThrownBy(() -> conversations.repondre(convId, clientId, "Encore une question"))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("fermer deux fois n'est pas une erreur")
    void fermetureIdempotente() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.getFirst());

        conversations.fermer(convId);
        assertThat(conversations.fermer(convId).estFermee()).isTrue();
    }

    @Test
    @DisplayName("on n'évalue qu'une conversation fermée, et une seule fois")
    void evaluation() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.getFirst());

        // Évaluer un échange en cours fausserait la mesure de satisfaction :
        // le responsable n'a pas fini son travail.
        assertThatThrownBy(() -> conversations.evaluer(convId, 5, "Parfait"))
                .isInstanceOf(ConflitEtat.class);

        conversations.fermer(convId);
        assertThat(conversations.evaluer(convId, 5, "Parfait").getNote()).isEqualTo(5);

        assertThatThrownBy(() -> conversations.evaluer(convId, 1, "Finalement non"))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("déjà été évaluée");
    }

    @Test
    @DisplayName("une note hors de 1 à 5 est refusée")
    void noteInvalide() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.getFirst());
        conversations.fermer(convId);

        assertThatThrownBy(() -> conversations.evaluer(convId, 0, null))
                .isInstanceOf(RegleMetierViolee.class);
    }
}
