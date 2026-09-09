package com.garah.api.surveillance;

import com.garah.api.commun.audit.GesteClient;
import com.garah.api.commun.audit.GesteClient.TypeGesteClient;
import com.garah.api.commun.securite.Acteur;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.surveillance.domaine.EcouteurActiviteClient;
import com.garah.api.surveillance.domaine.ServiceActiviteClient;
import com.garah.api.surveillance.domaine.VueActiviteClient;
import com.garah.api.surveillance.infra.ActiviteClientRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le parcours d'un client s'écrit, et se relit.
 *
 * <h2>🎯 Une table déclarée que personne ne remplissait</h2>
 *
 * <p>{@code activite_client} existe depuis V12, avec ses huit types et ses
 * deux index. Elle est restée <b>trois versions majeures</b> sans entité ni
 * écriture. Une table vide ne se plaint pas : elle se lit comme « aucun client
 * n'a rien fait », ce qui est indiscernable de « personne n'écrit ici ».</p>
 *
 * <p>C'est exactement le défaut trouvé ailleurs cette semaine — le journal
 * d'audit qui n'était alimenté par rien. La différence, cette fois, est qu'un
 * test le tient.</p>
 */
@SpringBootTest
@DisplayName("Parcours client")
class ParcoursClientTest {

    private static final String EMAIL = "parcours.client@garah.cm";

    @Autowired ServiceActiviteClient service;
    @Autowired EcouteurActiviteClient ecouteur;
    @Autowired ActiviteClientRepository activites;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired JdbcTemplate jdbc;

    private Long clientId;

    @BeforeEach
    void creerLeClient() {
        nettoyer();
        clientId = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.CLIENT, "Ateba", EMAIL, "x")).getId();

        // ⚠️ La clé étrangère porte sur `client`, pas sur `utilisateur`. Sans
        //    cette ligne, l'insertion échoue — et comme le service avale ses
        //    erreurs, le test passerait sans rien écrire. C'est précisément le
        //    piège qu'il doit éviter.
        jdbc.update("INSERT INTO client (id, code_client) VALUES (?, ?)",
                clientId, "CLI-PARCOURS-1");
    }

    @AfterEach
    void nettoyer() {
        utilisateurs.findByEmailIgnoreCase(EMAIL).ifPresent(u -> {
            jdbc.update("DELETE FROM activite_client WHERE client_id = ?", u.getId());
            jdbc.update("DELETE FROM client WHERE id = ?", u.getId());
            utilisateurs.delete(u);
        });
    }

    private Acteur leClient() {
        return new Acteur(clientId, "Ateba", EMAIL, "41.202.0.1", Acteur.Nature.CLIENT);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ un geste de client atterrit bien dans la table")
    void unGesteDeClientAtterritDansLaTable() {
        ecouteur.surGesteClient(GesteClient.de(leClient(), TypeGesteClient.COMMANDE,
                "numero", "CMD-2026-000001", "montant", 25000));

        var page = service.parcoursDe(clientId, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        VueActiviteClient ligne = page.getContent().getFirst();
        assertThat(ligne.type()).isEqualTo("COMMANDE");
        assertThat(ligne.contexte())
                .containsEntry("numero", "CMD-2026-000001")
                .containsEntry("montant", "25000");
    }

    @Test
    @DisplayName("⚠️ un geste INTERNE n'y atterrit pas")
    void unGesteInterneNyAtterritPas() {
        // Le journal des actions internes existe pour ça. Les mélanger ferait
        // disparaître les quelques gestes de la maison sous le trafic de la
        // boutique — c'est la raison d'être de la séparation, écrite en tête
        // de V12.
        Acteur responsable = new Acteur(clientId, "Ateba", EMAIL, "41.202.0.1",
                Acteur.Nature.INTERNE);

        ecouteur.surGesteClient(GesteClient.de(responsable, TypeGesteClient.COMMANDE,
                "numero", "CMD-2026-000002"));

        assertThat(service.parcoursDe(clientId, PageRequest.of(0, 10)).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ un client inconnu ne fait pas échouer le geste appelant")
    void unClientInconnuNeFaitPasEchouerLeGeste() {
        // C'est LE point. Une ligne de parcours qu'on n'arrive pas à écrire
        // est un désagrément ; une commande perdue parce qu'on n'a pas su
        // l'écrire est une vente perdue.
        Acteur fantome = new Acteur(999_999_999L, "Inconnu", "x@garah.cm",
                "41.202.0.1", Acteur.Nature.CLIENT);

        ecouteur.surGesteClient(GesteClient.de(fantome, TypeGesteClient.COMMANDE,
                "numero", "CMD-FANTOME"));
        // Aucune exception : le test s'arrête ici s'il y en avait une.
    }

    @Test
    @DisplayName("l'ordre suit l'index : du plus récent au plus ancien")
    void lOrdreSuitLIndex() {
        for (String numero : new String[] {"CMD-1", "CMD-2", "CMD-3"}) {
            ecouteur.surGesteClient(GesteClient.de(leClient(),
                    TypeGesteClient.COMMANDE, "numero", numero));
        }

        var lignes = service.parcoursDe(clientId, PageRequest.of(0, 10)).getContent();

        assertThat(lignes).hasSize(3);
        assertThat(lignes.getFirst().contexte()).containsEntry("numero", "CMD-3");
    }

    @Test
    @DisplayName("⚠️ l'adresse IP ne voyage pas jusqu'à l'écran")
    void lAdresseIpNeVoyagePasJusquALEcran() {
        ecouteur.surGesteClient(GesteClient.de(leClient(), TypeGesteClient.AJOUT_PANIER,
                "article", "Panier tresse", "quantite", 2));

        // Elle est écrite — la surveillance en a besoin — mais la vue ne la
        // porte pas : elle sert à détecter une anomalie, pas à comprendre un
        // parcours d'achat.
        assertThat(activites.findAll()).allSatisfy(
                a -> assertThat(a.getAdresseIp()).isEqualTo("41.202.0.1"));

        Map<String, String> contexte =
                service.parcoursDe(clientId, PageRequest.of(0, 10))
                        .getContent().getFirst().contexte();
        assertThat(contexte).doesNotContainKey("adresseIp");
    }
}
