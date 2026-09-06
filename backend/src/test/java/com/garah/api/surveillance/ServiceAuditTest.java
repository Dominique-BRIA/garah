package com.garah.api.surveillance;

import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.surveillance.domaine.AuditLog;
import com.garah.api.surveillance.domaine.ServiceAudit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le journal d'audit.
 *
 * <p>Non {@code @Transactional} : le service écrit en {@code REQUIRES_NEW},
 * donc dans sa propre transaction. Un rollback de test ne nettoierait rien —
 * et c'est justement le comportement qu'on veut vérifier.</p>
 */
@SpringBootTest
@DisplayName("Audit")
class ServiceAuditTest {

    private static final String EMAIL = "resp.audit@garah.cm";

    @Autowired ServiceAudit audit;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private Long acteurId;

    @BeforeEach
    void preparer() {
        nettoyer();
        acteurId = transactions.execute(s -> utilisateurs.save(new Utilisateur(
                TypeUtilisateur.RESPONSABLE, "Paul Mbarga", EMAIL, "x")).getId());
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM audit_log WHERE entite = 'tarification_test'");
        jdbc.update("DELETE FROM audit_log WHERE acteur_email = ?", EMAIL);
        jdbc.update("DELETE FROM utilisateur WHERE email = ?", EMAIL);
    }

    @Test
    @DisplayName("un changement enregistre l'avant ET l'après")
    void avantEtApres() {
        audit.changement(acteurId, "Paul Mbarga", EMAIL,
                "PRIX_MODIFIER", "tarification_test", 42L,
                "prixUnitaire", "15000.00", "16000.00", "41.202.0.1");

        AuditLog trace = audit.historiqueDe("tarification_test", 42L).getFirst();

        assertThat(trace.getAncienneValeur()).contains("15000.00");
        assertThat(trace.getNouvelleValeur()).contains("16000.00");
        assertThat(trace.getAction()).isEqualTo("PRIX_MODIFIER");
    }

    @Test
    @DisplayName("les valeurs sont interrogeables en jsonb, pas en texte")
    void jsonbInterrogeable() {
        audit.enregistrer(acteurId, "Paul Mbarga", EMAIL,
                "PRODUIT_MODIFIER", "tarification_test", 7L,
                Map.of("prix", "15000", "statut", "PUBLIE"),
                Map.of("prix", "12000", "statut", "PUBLIE"),
                "41.202.0.1");

        // C'est TOUT l'intérêt du jsonb : on veut pouvoir demander « qui a
        // changé le prix ? », pas relire des chaînes de caractères.
        Long trouvees = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE entite = 'tarification_test'
                   AND ancienne_valeur ->> 'prix' = '15000'
                """, Long.class);

        assertThat(trouvees).isEqualTo(1);
    }

    @Test
    @DisplayName("l'audit SURVIT à la suppression de son acteur")
    void survitALaSuppressionDeLActeur() {
        audit.enregistrer(acteurId, "Paul Mbarga", EMAIL,
                "PRODUIT_ARCHIVER", "tarification_test", 99L, null, null, null);

        jdbc.update("DELETE FROM utilisateur WHERE id = ?", acteurId);

        AuditLog trace = audit.historiqueDe("tarification_test", 99L).getFirst();

        // ON DELETE SET NULL, jamais CASCADE : un audit qu'on efface en
        // supprimant un utilisateur n'est pas un audit. Le nom COPIÉ reste.
        assertThat(trace.getUtilisateurId()).isNull();
        assertThat(trace.getActeurNom()).isEqualTo("Paul Mbarga");
        assertThat(trace.getActeurEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("l'audit survit à l'annulation de la transaction qu'il audite")
    void survitAuRollback() {
        try {
            transactions.executeWithoutResult(statut -> {
                audit.enregistrer(acteurId, "Paul Mbarga", EMAIL,
                        "PRODUIT_SUPPRIMER", "tarification_test", 123L, null, null, null);
                throw new IllegalStateException("L'opération échoue après coup");
            });
        } catch (IllegalStateException attendu) {
            // ignoré : c'est le scénario
        }

        // REQUIRES_NEW. La trace de la TENTATIVE reste — et c'est souvent la
        // trace la plus intéressante.
        assertThat(audit.historiqueDe("tarification_test", 123L)).hasSize(1);
    }
}
