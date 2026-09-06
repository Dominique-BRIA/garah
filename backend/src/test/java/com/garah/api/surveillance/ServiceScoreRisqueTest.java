package com.garah.api.surveillance;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.surveillance.domaine.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("Score de risque")
class ServiceScoreRisqueTest {

    @Autowired ServiceScoreRisque risque;
    @Autowired ServiceEvenementsSecurite securite;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long clientId;

    @BeforeEach
    void preparer() {
        Utilisateur u = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.CLIENT, "Mballa", "client.risque@garah.cm", "x"));
        clientId = clients.save(new Client(u, "CLI-RISQ-1")).getId();
        em.flush();
    }

    /** Insère un événement de sécurité directement : le service est REQUIRES_NEW. */
    private void evenement(String type, int combien) {
        for (int i = 0; i < combien; i++) {
            jdbc.update("""
                    INSERT INTO evenement_securite (utilisateur_id, type, gravite)
                    VALUES (?, ?, 'FAIBLE')
                    """, clientId, type);
        }
    }

    @Test
    @DisplayName("un client sans historique est à zéro")
    void clientPropre() {
        EvaluationRisque evaluation = risque.evaluer(clientId);

        assertThat(evaluation.score()).isZero();
        assertThat(evaluation.niveau()).isEqualTo(NiveauRisque.LOW);
        assertThat(evaluation.signaux()).isEmpty();
    }

    @Test
    @DisplayName("le score EXPLIQUE d'où il vient")
    void scoreExplicable() {
        evenement("ECHEC_CONNEXION", 7);
        evenement("ECHEC_PAIEMENT", 4);

        EvaluationRisque evaluation = risque.evaluer(clientId);

        // C'est la correction A14 : la spec exigeait « le score doit être
        // explicable » mais ne stockait qu'un nombre.
        assertThat(evaluation.estExplicable()).isTrue();
        assertThat(evaluation.signaux())
                .extracting(SignalRisque::code)
                .containsExactlyInAnyOrder("ECHECS_CONNEXION", "ECHECS_PAIEMENT");

        // L'écran d'administration affiche CES phrases, pas le nombre.
        assertThat(evaluation.signaux())
                .extracting(SignalRisque::libelle)
                .anyMatch(l -> l.contains("7 échec"));
    }

    @Test
    @DisplayName("un seul signal, même extrême, ne suffit pas à faire un CRITICAL")
    void plafonnementParSignal() {
        // Vingt échecs de connexion : ça arrive, on se trompe de mot de passe.
        evenement("ECHEC_CONNEXION", 20);

        EvaluationRisque evaluation = risque.evaluer(clientId);

        // Le signal est plafonné à 25. Sans ce plafond, un client distrait
        // serait traité comme un fraudeur.
        assertThat(evaluation.score()).isEqualTo(25);
        assertThat(evaluation.niveau()).isEqualTo(NiveauRisque.MEDIUM);
    }

    @Test
    @DisplayName("c'est l'ACCUMULATION de signaux différents qui fait le risque")
    void accumulationDeSignaux() {
        evenement("ECHEC_CONNEXION", 10);   // plafonné à 25
        evenement("ECHEC_PAIEMENT", 5);     // plafonné à 30

        EvaluationRisque evaluation = risque.evaluer(clientId);

        assertThat(evaluation.score()).isEqualTo(55);
        assertThat(evaluation.niveau()).isEqualTo(NiveauRisque.HIGH);
        assertThat(evaluation.signaux()).hasSize(2);
    }

    @Test
    @DisplayName("le niveau ne peut pas contredire le score")
    void niveauCoherent() {
        assertThat(NiveauRisque.pour(0)).isEqualTo(NiveauRisque.LOW);
        assertThat(NiveauRisque.pour(24.99)).isEqualTo(NiveauRisque.LOW);
        assertThat(NiveauRisque.pour(25)).isEqualTo(NiveauRisque.MEDIUM);
        assertThat(NiveauRisque.pour(50)).isEqualTo(NiveauRisque.HIGH);
        assertThat(NiveauRisque.pour(80)).isEqualTo(NiveauRisque.CRITICAL);
        assertThat(NiveauRisque.pour(100)).isEqualTo(NiveauRisque.CRITICAL);
    }

    @Test
    @DisplayName("la base refuse un niveau incohérent avec son score")
    void baseRefuseNiveauIncoherent() {
        // Seconde ligne de défense : les bornes sont écrites en Java ET en SQL.
        // Un niveau qui contredit son score rendrait tous les tableaux faux.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO score_risque_client (client_id, score, niveau, version_algorithme)
                VALUES (?, 90, 'LOW', 'v1.0')
                """, clientId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("le score est enregistré avec sa version et ses signaux")
    void enregistrementAvecExplication() {
        evenement("ECHEC_CONNEXION", 3);

        ScoreRisqueClient enregistre = risque.calculerEtEnregistrer(clientId);
        em.flush();

        assertThat(enregistre.getVersionAlgorithme()).isEqualTo("v1.0");
        assertThat(enregistre.getDetails()).contains("ECHECS_CONNEXION");

        // 3 échecs × 4 points = 12 → LOW. Trois erreurs de mot de passe ne
        // font pas un suspect : le signal est enregistré et expliqué, mais il
        // ne déclenche rien. C'est exactement le comportement voulu.
        assertThat(enregistre.getScore()).isEqualByComparingTo("12.00");
        assertThat(enregistre.getNiveau()).isEqualTo(NiveauRisque.LOW);
    }

    @Test
    @DisplayName("chaque calcul ajoute une ligne, il n'écrase pas la précédente")
    void historiqueConserve() {
        risque.calculerEtEnregistrer(clientId);
        evenement("ECHEC_PAIEMENT", 4);
        risque.calculerEtEnregistrer(clientId);
        em.flush();

        // Savoir qu'un client était à 78 le 6 et à 12 le 20 raconte quelque
        // chose que la valeur courante ne dit pas.
        assertThat(risque.historique(clientId)).hasSize(2);
    }

    @Test
    @DisplayName("un score élevé ouvre UNE alerte, pas une par calcul")
    void alerteUnique() {
        evenement("ECHEC_CONNEXION", 10);
        evenement("ECHEC_PAIEMENT", 5);

        risque.calculerEtEnregistrer(clientId);
        risque.calculerEtEnregistrer(clientId);
        risque.calculerEtEnregistrer(clientId);
        em.flush();

        // Recalculer le score toutes les heures ne doit pas noyer l'Admin
        // sous vingt alertes pour la même situation.
        Long ouvertes = jdbc.queryForObject("""
                SELECT count(*) FROM alerte_securite
                 WHERE client_id = ? AND statut = 'OUVERTE'
                """, Long.class, clientId);
        assertThat(ouvertes).isEqualTo(1);
    }

    @Test
    @DisplayName("la surveillance n'a AUCUNE méthode qui bloque un compte")
    void surveillanceObserveMaisNeDecidePas() {
        evenement("ECHEC_CONNEXION", 20);
        evenement("ECHEC_PAIEMENT", 10);

        risque.calculerEtEnregistrer(clientId);
        em.flush();
        em.clear();

        // Règle fondatrice n°2. Le compte reste ACTIF : un score CRITICAL
        // alerte un humain, il ne bloque personne.
        String statut = jdbc.queryForObject(
                "SELECT statut FROM utilisateur WHERE id = ?", String.class, clientId);
        assertThat(statut).isEqualTo("ACTIF");
    }

    @Test
    @DisplayName("une alerte se clôt avec une décision écrite")
    void decisionObligatoire() {
        evenement("ECHEC_CONNEXION", 10);
        evenement("ECHEC_PAIEMENT", 5);
        risque.calculerEtEnregistrer(clientId);
        em.flush();

        AlerteSecurite alerte = risque.fileDAlertes().stream()
                .filter(a -> a.getClientId().equals(clientId))
                .findFirst().orElseThrow();

        // Une alerte traitée sans décision ne sert à rien : personne ne saura
        // si le compte a été vérifié ou classé pour vider la file.
        assertThatThrownBy(() -> risque.trancher(alerte.getId(), true, 1L, "  "))
                .isInstanceOf(RegleMetierViolee.class);

        risque.trancher(alerte.getId(), false, null,
                "Client connu, déménagement récent, aucun signe de fraude.");

        assertThatThrownBy(() -> risque.trancher(alerte.getId(), true, null, "Encore"))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("la file d'alertes montre les plus graves en premier")
    void fileTrieeParGravite() {
        evenement("ECHEC_CONNEXION", 10);
        evenement("ECHEC_PAIEMENT", 10);
        risque.calculerEtEnregistrer(clientId);
        em.flush();

        // Un tri par date noierait une alerte CRITIQUE sous trente FAIBLES.
        assertThat(risque.fileDAlertes())
                .filteredOn(a -> a.getClientId().equals(clientId))
                .isNotEmpty()
                .first()
                .extracting(AlerteSecurite::getGravite)
                .isIn(GraviteEvenement.HAUTE, GraviteEvenement.CRITIQUE);
    }
}
