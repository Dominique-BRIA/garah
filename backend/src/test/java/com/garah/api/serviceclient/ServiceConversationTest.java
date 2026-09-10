package com.garah.api.serviceclient;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RessourceIntrouvable;
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
        assertThat(conv.getPrisPar()).isNull();
        assertThat(conversations.fileDAttente()).extracting(Conversation::getId).contains(conv.getId());
    }

    @Test
    @DisplayName("⚠️ répondre à un client PREND la conversation, sans geste de plus")
    void repondrePrendLaMain() {
        // 🎯 L'exiger en deux gestes — « Prendre », puis « Répondre » —
        //    produisait des conversations traitées mais toujours affichées
        //    WAITING : un collègue les rouvrait pour découvrir qu'on y avait
        //    déjà répondu.
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        Long agent = responsableIds.getFirst();

        conversations.repondre(convId, agent, "Bonjour, je regarde cela.");

        Conversation apres = conversations.parId(convId);
        assertThat(apres.getStatut()).isEqualTo(StatutConversation.ASSIGNED);
        assertThat(apres.getPrisPar()).isEqualTo(agent);
        assertThat(conversations.historique(convId)).hasSize(1);
    }

    @Test
    @DisplayName("⚠️ mais le CLIENT ne prend jamais sa propre conversation")
    void leClientNePrendPasLaSienne() {
        // Sans cette garde, la file d'attente se viderait toute seule dès le
        // deuxième message du client — et plus personne ne verrait qu'il
        // attend.
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();

        conversations.repondre(convId, clientId, "Je précise ma question.");

        Conversation apres = conversations.parId(convId);
        assertThat(apres.getStatut()).isEqualTo(StatutConversation.WAITING);
        assertThat(apres.getPrisPar()).isNull();
    }

    @Test
    @DisplayName("⚠️ répondre à une conversation DÉJÀ prise ne change pas de main")
    void uneConversationPriseNeChangePasDeMain() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        Long premier = responsableIds.getFirst();
        conversations.prendre(convId, premier);

        // Un collègue qui répond en renfort n'en devient pas le titulaire :
        // la réaffectation est un geste explicite, avec son motif.
        conversations.repondre(convId, responsableIds.get(1), "Je complète.");

        assertThat(conversations.parId(convId).getPrisPar()).isEqualTo(premier);
    }

    @Test
    @DisplayName("⚠️ la vue rend les NOMS, pas seulement les identifiants")
    void laVueRendLesNoms() {
        // 🎯 Sans cette résolution, l'écran recevait « prisPar: 7 » et n'avait
        //    aucun moyen d'en tirer un nom. La question « qui a clos ? »
        //    serait restée sans réponse alors que la donnée était là.
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        Long agent = responsableIds.getFirst();
        conversations.prendre(convId, agent);
        conversations.fermer(convId, agent);

        var vue = conversations.vue(convId);

        assertThat(vue.prisPar()).isEqualTo(agent);
        assertThat(vue.prisParNom()).isNotBlank();
        assertThat(vue.closPar()).isEqualTo(agent);
        assertThat(vue.closParNom()).isNotBlank();
    }

    @Test
    @DisplayName("une conversation jamais prise ne nomme personne, sans tomber")
    void jamaisPriseNeNommePersonne() {
        // ⚠️ `Map.of()` refuse une clé nulle même en LECTURE : c'est ce qui
        //    faisait tomber la liste dès qu'une conversation attendait.
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();

        var vue = conversations.vue(convId);

        assertThat(vue.prisPar()).isNull();
        assertThat(vue.prisParNom()).isNull();
        assertThat(vue.closParNom()).isNull();
    }

    @Test
    @DisplayName("⚠️ on sait QUI a clos, pas seulement quand")
    void onSaitQuiAClos() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        Long agent = responsableIds.getFirst();
        conversations.prendre(convId, agent);

        conversations.fermer(convId, agent);

        Conversation close = conversations.parId(convId);
        assertThat(close.getClosPar()).isEqualTo(agent);
        assertThat(close.getDateCloture()).isNotNull();
    }

    @Test
    @DisplayName("prendre une conversation l'attribue et la sort de la file")
    void prise() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();

        Conversation prise = conversations.prendre(convId, responsableIds.getFirst());

        assertThat(prise.getStatut()).isEqualTo(StatutConversation.ASSIGNED);
        assertThat(prise.getPrisPar()).isEqualTo(responsableIds.getFirst());
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

        assertThat(apres.getPrisPar()).isEqualTo(responsableIds.get(1));
        // Les deux affectations sont conservées : qui l'a eue, quand, pourquoi.
        assertThat(conversations.historique(convId)).hasSize(2);
    }

    @Test
    @DisplayName("on ne répond plus dans une conversation fermée")
    void pasDeReponseApresFermeture() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.getFirst());
        conversations.fermer(convId, responsableIds.getFirst());

        assertThatThrownBy(() -> conversations.repondre(convId, clientId, "Encore une question"))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("fermer deux fois n'est pas une erreur")
    void fermetureIdempotente() {
        Long convId = conversations.ouvrir(clientId, "Sujet", "Bonjour").getId();
        conversations.prendre(convId, responsableIds.getFirst());

        conversations.fermer(convId, responsableIds.getFirst());
        assertThat(conversations.fermer(convId, responsableIds.getFirst()).estFermee()).isTrue();
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

        conversations.fermer(convId, responsableIds.getFirst());
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
        conversations.fermer(convId, responsableIds.getFirst());

        assertThatThrownBy(() -> conversations.evaluer(convId, 0, null))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("repondre ne fait pas monter le compteur de non lus")
    void reponseAgentPasComptee() {
        // 🎯 CE QUI SE PASSAIT A L'ECRAN : l'agent tapait sa reponse, et la
        //    pastille passait de 2 non lus a 3. On repondait, et le dossier
        //    avait l'air d'attendre davantage.
        //
        //    `lu` est faux a l'ecriture pour TOUT message, quel qu'en soit
        //    l'auteur. L'agregat comptait donc la reponse de l'agent parmi les
        //    messages qui attendent une reponse de l'agent.
        Long convId = conversations.ouvrir(clientId, "Question prix", "Bonjour").getId();
        conversations.repondre(convId, clientId, "Es-ce gratuit ?");

        assertThat(nonLusDe(convId)).isEqualTo(2);

        Long responsableId = responsableIds.getFirst();
        conversations.prendre(convId, responsableId);
        conversations.repondre(convId, responsableId, "Bonjour, elle coute 2000F");

        // Le total monte — trois messages ont bien ete ecrits.
        assertThat(nombreMessagesDe(convId)).isEqualTo(3);

        // ⚠️ Les non lus, EUX, ne bougent pas : ce sont toujours les deux
        //    memes messages du client qui attendent.
        assertThat(nonLusDe(convId)).isEqualTo(2);
    }

    private long nonLusDe(Long convId) {
        return vueDe(convId).nonLus();
    }

    private long nombreMessagesDe(Long convId) {
        return vueDe(convId).nombreMessages();
    }

    private ResumeConversation vueDe(Long convId) {
        return conversations.administration(null, null, PageRequest.of(0, 25))
                .getContent().stream()
                .filter(c -> c.id().equals(convId))
                .findFirst()
                .orElseThrow();
    }

    // -------------------------------------------------------------------------
    // L'Assistance GARAH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("l'assistance est unique par client, et naît hors de la file")
    void assistanceUniqueEtHorsFile() {
        Long premiere = conversations.assistance(clientId).getId();
        Long seconde = conversations.assistance(clientId).getId();

        // Deux demandes ne créent pas deux assistances.
        assertThat(seconde).isEqualTo(premiere);

        VueConversation vue = conversations.vue(premiere);
        assertThat(vue.assistance()).isTrue();
        // ⚠️ INFORMATION et non WAITING : tant que le client n'a rien demandé,
        //    personne n'attend personne, et la file de l'équipe reste vide.
        assertThat(vue.statut()).isEqualTo("INFORMATION");
    }

    @Test
    @DisplayName("le client écrit : elle entre dans la file, on y répond, on ne peut pas la clore")
    void assistanceSuitLaFileMaisNeSeClotPas() {
        Long id = conversations.ecrireALAssistance(clientId,
                "Bonjour, j'ai une question sur ma livraison.").conversationId();
        assertThat(conversations.vue(id).statut()).isEqualTo("WAITING");

        Long agent = responsableIds.getFirst();
        conversations.repondre(id, agent, "Bonjour, je regarde.");
        assertThat(conversations.vue(id).statut()).isEqualTo("ASSIGNED");

        // 🎯 La règle qui la distingue de toutes les autres discussions.
        assertThatThrownBy(() -> conversations.fermer(id, agent))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("écrire comme Assistance ne prend pas la conversation")
    void ecrireCommeAssistanceNePrendPas() {
        Long marketing = responsableIds.get(1);

        VueMessage envoye = conversations.ecrireCommeAssistance(
                clientId, marketing, "Cette semaine, -10 % sur les chaussures.");

        VueConversation vue = conversations.vue(envoye.conversationId());
        // Une annonce reste une annonce : pas de responsable, pas de file.
        assertThat(vue.statut()).isEqualTo("INFORMATION");
        assertThat(vue.prisPar()).isNull();
        // L'auteur réel est gardé : le client lit « GARAH », pas son nom.
        assertThat(vue.messages()).singleElement()
                .satisfies(m -> assertThat(m.expediteurId()).isEqualTo(marketing));

        // Le client répond : là, quelqu'un attend vraiment.
        conversations.ecrireALAssistance(clientId, "Merci, c'est valable en boutique ?");
        assertThat(conversations.vue(vue.id()).statut()).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("écrire à un client inconnu est refusé proprement")
    void assistanceClientInconnu() {
        assertThatThrownBy(() -> conversations.ecrireCommeAssistance(
                -42L, responsableIds.getFirst(), "Bonjour"))
                .isInstanceOf(RessourceIntrouvable.class);
    }
}
