package com.garah.api.commun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ce que {@code GET /api/configuration} annonce doit exister vraiment.
 *
 * <h2>Le défaut que ce test aurait attrapé</h2>
 *
 * <p>La route annonçait la langue <b>{@code sag}</b> — le code ISO 639-2 du
 * sängö — alors que toute la plateforme utilise <b>{@code sg}</b>, celui de la
 * norme 639-1 : c'est la clé primaire de la table {@code langue}, et la colonne
 * {@code utilisateur.langue} est un {@code char(2)} avec une clé étrangère
 * dessus.</p>
 *
 * <p><b>Rien n'échouait.</b> Le contrôleur renvoyait sa liste, les tests
 * passaient, les journaux étaient muets. Le défaut n'existait que pour un
 * frontend construisant son sélecteur de langue à partir de cette liste : il
 * aurait proposé « sag », la validation l'aurait rejeté en silence et
 * enregistré « fr ». Choisir le sängö n'aurait tout simplement jamais rien
 * fait.</p>
 *
 * <p>🎯 <b>La leçon est celle de {@code SecuriteHttpTest}, appliquée aux
 * données :</b> une valeur que trois frontends consomment et qu'aucun test ne
 * confronte à sa source finit par diverger d'elle.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Configuration annoncée")
class ConfigurationAnnonceeTest {

    @Autowired MockMvc http;
    @Autowired JdbcTemplate sql;
    @Autowired ObjectMapper json;

    /**
     * On compare à la <b>table de référence</b>, pas à une liste recopiée dans
     * le test.
     *
     * <p>Réécrire {@code ["fr", "en", "sg"]} ici ne prouverait rien : ce serait
     * une troisième copie de la même liste, qui divergerait à son tour. La
     * table {@code langue} est la source — c'est elle que les clés étrangères
     * désignent.</p>
     */
    @Test
    @DisplayName("chaque langue annoncée existe dans le référentiel")
    void lesLanguesAnnonceesExistent() throws Exception {
        String corps = http.perform(get("/api/configuration"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode noeud = json.readTree(corps).get("langues");
        assertThat(noeud).as("la configuration doit annoncer des langues").isNotNull();

        List<String> annoncees = new ArrayList<>();
        noeud.forEach(n -> annoncees.add(n.asText()));

        List<String> referentiel =
                sql.queryForList("SELECT code FROM langue WHERE actif", String.class);

        assertThat(annoncees)
                .as("une langue annoncée qui n'est pas dans la table `langue` "
                        + "sera rejetée en silence à l'enregistrement")
                .isNotEmpty()
                .allMatch(referentiel::contains);
    }

    /**
     * La première annoncée est celle par défaut : c'est le contrat que les
     * frontends appliquent pour présélectionner un choix.
     */
    @Test
    @DisplayName("la langue par défaut est annoncée en premier")
    void laLangueParDefautEstEnTete() throws Exception {
        String corps = http.perform(get("/api/configuration"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String premiere = json.readTree(corps).get("langues").get(0).asText();
        String parDefaut = sql.queryForObject(
                "SELECT code FROM langue WHERE par_defaut LIMIT 1", String.class);

        assertThat(premiere).isEqualTo(parDefaut);
    }

    /**
     * La devise doit être formatable par les frontends.
     *
     * <p>{@code montantLisible()} traduit {@code XAF} en « FCFA ». Une devise
     * annoncée que le frontend ne connaît pas s'afficherait telle quelle à côté
     * des montants — visible, mais seulement en production.</p>
     */
    @Test
    @DisplayName("la devise annoncée est celle du pays d'exploitation")
    void laDeviseEstXaf() throws Exception {
        String corps = http.perform(get("/api/configuration"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(json.readTree(corps).get("devise").asText()).isEqualTo("XAF");
    }
}
