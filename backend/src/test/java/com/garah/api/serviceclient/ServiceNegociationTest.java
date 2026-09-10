package com.garah.api.serviceclient;

import com.garah.api.catalogue.domaine.CategorieProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.domaine.ServiceTarification;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.Responsable;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.serviceclient.domaine.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("Négociation de prix")
class ServiceNegociationTest {

    @Autowired ServiceNegociation negociation;
    @Autowired com.garah.api.serviceclient.infra.PropositionPrixRepository depotPropositions;
    @Autowired com.garah.api.serviceclient.infra.ConversationRepository depotConversations;
    @Autowired com.garah.api.commun.audit.JournalActions journalActions;
    @Autowired ServiceConversation conversations;
    @Autowired ServiceCatalogue catalogue;
    @Autowired ServiceTarification tarification;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired ResponsableRepository responsables;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long clientId;
    private Long responsableId;
    private Long varianteId;
    private Long conversationId;

    @BeforeEach
    void preparer() {
        Utilisateur u = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.CLIENT, "Nkolo", "client.nego@garah.cm", "x"));
        u.marquerEmailVerifie();
        clientId = clients.save(new Client(u, "CLI-NEGO-1")).getId();

        // auteur_id est une clé étrangère vers utilisateur. Un identifiant
        // inventé (« 1L ») passe ou échoue selon le contenu de la base — donc
        // le test serait fragile. On crée un vrai utilisateur.
        //
        // Et il faut AUSSI la ligne responsable : conversation.responsable_id
        // pointe vers responsable, pas vers utilisateur. C'est l'invariant
        // I-06 (« un utilisateur de type RESPONSABLE a sa ligne fille »), que
        // SQL ne sait pas imposer — donc qu'on oublie.
        Utilisateur compteResponsable = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.RESPONSABLE, "Biya", "resp.nego@garah.cm", "x"));
        compteResponsable.marquerEmailVerifie();
        responsableId = responsables.save(
                new Responsable(compteResponsable, "M-NEGO-R1")).getId();

        Long marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type)
                VALUES ('M-NEGO-1', 'Marchand négo', 'EXTERNE') RETURNING id
                """, Long.class);
        Long categorieId = categories.save(new CategorieProduit("Négociation", null)).getId();
        Long produitId = catalogue.creerProduit(marchandId, categorieId,
                "REF-NEGO-1", "Article négociable", null).id();
        em.flush();

        varianteId = variantes.findByProduitId(produitId).getFirst().getId();
        tarification.definirPalier(varianteId, 1, null, new BigDecimal("15000.00"));

        conversationId = conversations.ouvrir(clientId, "Négociation", "Votre meilleur prix ?").getId();
        em.flush();
    }

    @Test
    @DisplayName("une proposition dit sur quoi, combien, par qui et jusqu'à quand")
    void propositionComplete() {
        PropositionPrix p = negociation.proposer(conversationId, varianteId, 20,
                new BigDecimal("12000.00"), clientId, SensProposition.CLIENT, null);

        // Les quatre informations qui manquaient au modèle initial (A6).
        assertThat(p.getVarianteId()).isEqualTo(varianteId);
        assertThat(p.getQuantite()).isEqualTo(20);
        assertThat(p.getAuteurId()).isEqualTo(clientId);
        assertThat(p.getDateExpiration()).isAfter(p.getDateCreation());
        assertThat(p.getStatut()).isEqualTo(StatutProposition.PROPOSEE);
    }

    @Test
    @DisplayName("un responsable ne peut pas proposer plus cher que le tarif public")
    void pasDeNegociationALaHausse() {
        assertThatThrownBy(() -> negociation.proposer(conversationId, varianteId, 5,
                new BigDecimal("18000.00"), responsableId, SensProposition.RESPONSABLE, null))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("dépasse le tarif public");
    }

    @Test
    @DisplayName("une contre-proposition refuse la précédente et s'y rattache")
    void contreProposition() {
        PropositionPrix client = negociation.proposer(conversationId, varianteId, 20,
                new BigDecimal("12000.00"), clientId, SensProposition.CLIENT, null);

        PropositionPrix contre = negociation.contreProposer(client.getId(),
                new BigDecimal("13500.00"), responsableId, SensProposition.RESPONSABLE, null);

        // Une contre-offre EST un refus : laisser les deux ouvertes permettrait
        // au client d'accepter l'ancienne après avoir vu la nouvelle.
        assertThat(client.getStatut()).isEqualTo(StatutProposition.REFUSEE);
        assertThat(contre.getPropositionParenteId()).isEqualTo(client.getId());
        assertThat(negociation.fil(conversationId)).hasSize(2);
    }

    /**
     * Simule le passage du temps.
     *
     * <p>Ma première version passait une {@code Duration} négative pour créer
     * une proposition déjà expirée. La base l'a refusée :
     * {@code proposition_prix_expiration_posterieure} exige
     * {@code date_expiration > date_creation}.</p>
     *
     * <p>La contrainte a raison — une telle ligne n'a aucun sens métier. On
     * crée donc la proposition normalement, puis on <b>antidate</b> son
     * expiration. C'est la bonne façon de tester une règle temporelle : agir
     * sur les données, pas contourner le modèle.</p>
     */
    private void antidaterExpiration(Long propositionId) {
        em.flush();

        // ⚠️ Antidater la SEULE expiration échoue aussi : la contrainte est
        // vérifiée à l'UPDATE comme à l'INSERT. Il faut reculer la création
        // en même temps.
        //
        // La base refuse donc de fabriquer un passé incohérent, jusque dans
        // les tests. C'est exactement ce qu'on lui demande.
        jdbc.update("""
                UPDATE proposition_prix
                   SET date_creation   = now() - interval '10 days',
                       date_expiration = now() - interval '1 day'
                 WHERE id = ?
                """, propositionId);

        em.clear();
    }

    @Test
    @DisplayName("une proposition expirée ne peut plus être acceptée")
    void propositionExpiree() {
        PropositionPrix p = negociation.proposer(conversationId, varianteId, 10,
                new BigDecimal("11000.00"), clientId, SensProposition.CLIENT, Duration.ofDays(1));
        antidaterExpiration(p.getId());

        // I-33. Sans ce contrôle, un client accepterait en octobre un prix
        // proposé en mars. Et ce ne peut PAS être une contrainte SQL : un
        // CHECK ne sait pas lire l'heure courante (chapitre 05 §7).
        assertThatThrownBy(() -> negociation.accepter(p.getId(), SensProposition.RESPONSABLE))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("expiré");
    }

    @Test
    @DisplayName("le travail périodique marque les propositions dépassées")
    void expirationPeriodique() {
        PropositionPrix p = negociation.proposer(conversationId, varianteId, 10,
                new BigDecimal("11000.00"), clientId, SensProposition.CLIENT, Duration.ofDays(1));
        antidaterExpiration(p.getId());

        assertThat(negociation.expirerLesDepassees()).isEqualTo(1);
        assertThat(negociation.fil(conversationId).getFirst().getStatut())
                .isEqualTo(StatutProposition.EXPIREE);
    }

    @Test
    @DisplayName("une proposition ne sert qu'une fois")
    void consommationUnique() {
        PropositionPrix p = negociation.proposer(conversationId, varianteId, 20,
                new BigDecimal("12000.00"), clientId, SensProposition.CLIENT, null);
        negociation.accepter(p.getId(), SensProposition.RESPONSABLE);

        negociation.consommer(p.getId());
        assertThat(p.getStatut()).isEqualTo(StatutProposition.CONSOMMEE);

        // Sinon une remise accordée pour 20 pièces s'appliquerait à toutes
        // les commandes suivantes.
        assertThatThrownBy(() -> negociation.consommer(p.getId()))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("on ne consomme pas une proposition non acceptée")
    void consommationSansAcceptation() {
        PropositionPrix p = negociation.proposer(conversationId, varianteId, 20,
                new BigDecimal("12000.00"), clientId, SensProposition.CLIENT, null);

        assertThatThrownBy(() -> negociation.consommer(p.getId()))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("on ne négocie plus dans une conversation fermée")
    void conversationFermee() {
        conversations.prendre(conversationId, responsableId);
        conversations.fermer(conversationId, responsableId);
        em.flush();

        assertThatThrownBy(() -> negociation.proposer(conversationId, varianteId, 5,
                new BigDecimal("14000.00"), clientId, SensProposition.CLIENT, null))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("fermée");
    }

    @Test
    @DisplayName("prendre une conversation sans responsable est refusé proprement")
    void priseSansResponsable() {
        // Sans la garde du service, la base refuserait avec
        // « conversation_responsable_coherent » — un message que personne
        // ne peut interpréter côté appelant.
        assertThatThrownBy(() -> conversations.prendre(conversationId, null))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("⚠️ on n'accepte pas sa propre offre")
    void pasSaPropreOffre() {
        // 🎯 LA FAILLE QUE CE TEST FERME.
        //
        //    Un client proposait 10 FCFA — ses offres ne sont pas comparees au
        //    tarif — puis acceptait LUI-MEME sa proposition. Depuis que le prix
        //    negocie s'applique a la commande (D-44), il payait 10 FCFA.
        PropositionPrix p = negociation.proposer(conversationId, varianteId, 1,
                new BigDecimal("10.00"), clientId, SensProposition.CLIENT, null);

        assertThatThrownBy(() -> negociation.accepter(p.getId(), SensProposition.CLIENT))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("propre proposition");
        assertThat(negociation.fil(conversationId).getFirst().getStatut())
                .isEqualTo(StatutProposition.PROPOSEE);
    }

    @Test
    @DisplayName("⚠️ fermée, la négociation l'est partout : ni offre, ni acceptation, ni prix appliqué")
    void negociationFermee() {
        // Les prix sont fixes (D-46). Une offre acceptee AVANT la fermeture ne
        // doit pas davantage s'appliquer : fermer l'ecran seul ne fermait rien.
        PropositionPrix acceptee = negociation.proposer(conversationId, varianteId, 20,
                new BigDecimal("12000.00"), clientId, SensProposition.CLIENT, null);
        negociation.accepter(acceptee.getId(), SensProposition.RESPONSABLE);

        ServiceNegociation fermee = new ServiceNegociation(
                depotPropositions, depotConversations, tarification, journalActions, false);

        assertThat(fermee.prixNegocie(clientId, varianteId, 20))
                .as("aucun prix negocie ne s'applique")
                .isEmpty();
        assertThatThrownBy(() -> fermee.proposer(conversationId, varianteId, 1,
                new BigDecimal("10.00"), clientId, SensProposition.CLIENT, null))
                .isInstanceOf(com.garah.api.commun.erreur.RegleMetierViolee.class)
                .hasMessageContaining("ne se négocient pas");
        assertThatThrownBy(() -> fermee.accepter(acceptee.getId(), SensProposition.RESPONSABLE))
                .isInstanceOf(com.garah.api.commun.erreur.RegleMetierViolee.class);
    }
}
