package com.garah.api.commun.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Point de contrôle minimal : l'API répond-elle, et voit-elle la base ?
 *
 * <p>Utile dès maintenant pour vérifier que la chaîne complète fonctionne, et
 * plus tard pour la sonde de disponibilité de Render (D-14) — dont l'offre
 * gratuite endort l'instance : le premier appel après inactivité est lent,
 * ce n'est pas un bug.</p>
 */
@RestController
@RequestMapping("/api/sante")
public class ControleurSante {

    private final JdbcTemplate jdbc;

    public ControleurSante(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> sante() {
        // On exclut flyway_schema_history : c'est la table de travail de
        // l'outil de migration, pas une table du modèle.
        Integer tables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'
                """, Integer.class);

        Integer versionSchema = jdbc.queryForObject("""
                SELECT max(version::int) FROM flyway_schema_history WHERE success
                """, Integer.class);

        Integer permissions = jdbc.queryForObject(
                "SELECT count(*) FROM cas_utilisation WHERE statut = 'ACTIF'", Integer.class);

        return Map.of(
                "etat", "OK",
                "tables", tables,
                "versionSchema", versionSchema,
                "permissionsActives", permissions);
    }
}
