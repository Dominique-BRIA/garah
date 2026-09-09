package com.garah.api.commun.web;

import com.garah.api.commun.email.PasserelleEmail;
import com.garah.api.commun.stockage.DepotFichiers;
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
 *
 * <h2>⚠️ Ce que cette route dit, et pourquoi elle le dit publiquement</h2>
 *
 * <p>Elle annonce si le <b>courriel</b> et le <b>stockage</b> sont configurés.
 * Ce sont deux services dont l'absence ne se voit pas : sans SMTP, la
 * passerelle n'échoue pas — elle écrit le message dans les journaux, et
 * l'inscription répond {@code 201} comme si tout allait bien.</p>
 *
 * <p>Le client découvre alors la panne <b>à la caisse</b> : commander exige une
 * adresse confirmée, et il ne peut pas la confirmer. Entre son inscription et
 * ce refus, rien ne l'avertit.</p>
 *
 * <p>Le compromis est assumé : dire « le courriel n'est pas configuré »
 * renseigne aussi qui n'y a pas droit. Mais cette route publie déjà la version
 * du schéma et le nombre de tables, et une panne muette coûte plus cher qu'un
 * booléen. Le jour où l'on jugera l'inverse, c'est ici qu'il faudra la
 * fermer.</p>
 */
@RestController
@RequestMapping("/api/sante")
public class ControleurSante {

    private final JdbcTemplate jdbc;
    private final PasserelleEmail courriels;
    private final DepotFichiers fichiers;

    public ControleurSante(JdbcTemplate jdbc,
                           PasserelleEmail courriels,
                           DepotFichiers fichiers) {
        this.jdbc = jdbc;
        this.courriels = courriels;
        this.fichiers = fichiers;
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
                "permissionsActives", permissions,

                // 🎯 DEUX SERVICES DONT L ABSENCE NE SE VOIT PAS.
                //
                //    Sans SMTP, la passerelle ne LEVE PAS : elle ecrit le
                //    message dans les journaux. C'est le bon comportement en
                //    developpement — on suit le lien de confirmation sans
                //    monter un serveur de messagerie — et le pire en
                //    production : l'inscription repond 201, l'ecran annonce
                //    « un lien est parti », et personne ne recoit rien.
                //
                // ⚠️ Le client decouvre la panne A LA CAISSE : commander exige
                //    une adresse confirmee, et il ne peut pas la confirmer.
                //    Entre l'inscription et ce refus, rien ne signale quoi que
                //    ce soit.
                //
                //    Deux booleens ici transforment cette panne muette en
                //    question a laquelle on repond en une requete.
                "courrielConfigure", courriels.estConfigure(),
                "stockageConfigure", fichiers.estConfigure());
    }
}
