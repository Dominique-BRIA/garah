package com.garah.api.surveillance.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Les mesures brutes qui alimentent le score de risque.
 *
 * <p><b>Pourquoi du {@code JdbcTemplate} et pas Spring Data.</b> Ces requêtes
 * traversent trois domaines — sécurité, IAM, commerce — et ne font que
 * <b>compter</b>. Les exprimer en Spring Data obligerait soit à injecter trois
 * services, soit à déclarer un {@code Repository} sur une entité arbitraire.</p>
 *
 * <p>Ma première version écrivait {@code extends Repository<Object, Long>} :
 * Spring Data JPA l'a refusée au démarrage — {@code Not a managed type: class
 * java.lang.Object}. Le message est sec, et il a raison : un repository JPA
 * <b>appartient</b> à une entité. Ces comptages n'en ont aucune.</p>
 *
 * <p>C'est un des rares cas où lire directement les tables est le bon choix :
 * on ne modifie rien, et on ne veut surtout pas que la surveillance devienne
 * un nœud dont tout le reste dépend (chapitre 06 §4).</p>
 */
@Component
public class SignauxRisqueRepository {

    private final JdbcTemplate jdbc;

    public SignauxRisqueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long echecsConnexion(Long clientId, Instant depuis) {
        return compter("""
                SELECT count(*) FROM evenement_securite
                 WHERE utilisateur_id = ? AND type = 'ECHEC_CONNEXION' AND date_heure > ?
                """, clientId, depuis);
    }

    public long echecsPaiement(Long clientId, Instant depuis) {
        return compter("""
                SELECT count(*) FROM evenement_securite
                 WHERE utilisateur_id = ? AND type = 'ECHEC_PAIEMENT' AND date_heure > ?
                """, clientId, depuis);
    }

    public long nouveauxAppareils(Long clientId, Instant depuis) {
        return compter("""
                SELECT count(*) FROM appareil_connu
                 WHERE utilisateur_id = ? AND NOT de_confiance AND premiere_utilisation > ?
                """, clientId, depuis);
    }

    public long commandesRecentes(Long clientId, Instant depuis) {
        return compter("""
                SELECT count(*) FROM commande
                 WHERE client_id = ? AND date_creation > ?
                """, clientId, depuis);
    }

    public long annulationsRecentes(Long clientId, Instant depuis) {
        return compter("""
                SELECT count(*) FROM commande
                 WHERE client_id = ? AND statut = 'ANNULEE' AND date_creation > ?
                """, clientId, depuis);
    }

    private long compter(String sql, Long clientId, Instant depuis) {
        Long resultat = jdbc.queryForObject(sql, Long.class, clientId, Timestamp.from(depuis));
        return resultat == null ? 0L : resultat;
    }
}
