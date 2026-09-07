package com.garah.api.logistique;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.logistique.domaine.Itineraire;
import com.garah.api.logistique.domaine.Lieu;
import com.garah.api.logistique.domaine.ServiceItineraire;
import com.garah.api.logistique.domaine.TypeLieu;
import com.garah.api.logistique.domaine.VueItineraire;
import com.garah.api.logistique.infra.LieuRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les trajets types.
 *
 * <p>Le point sensible de tout ce module tient en une contrainte :
 * {@code etape_itineraire_ordre_unique} (I-34) refuse deux étapes de même rang
 * dans un même itinéraire, <b>même transitoirement</b>. C'est elle qui décide
 * de la forme du service — on remplace en bloc plutôt que de modifier rang par
 * rang — et c'est donc elle que ces tests visent en priorité.</p>
 */
@SpringBootTest
@DisplayName("Itinéraires : les trajets types")
class ServiceItineraireTest {

    private static final String MARQUEUR = "ITI-TEST";

    @Autowired ServiceItineraire itineraires;
    @Autowired LieuRepository lieux;
    @Autowired JdbcTemplate jdbc;

    private Long douala;
    private Long bertoua;
    private Long garouaBoulai;
    private Long bangui;
    private Long bangui2;

    @BeforeEach
    void preparerLeReseau() {
        nettoyer();

        douala = lieu(TypeLieu.ENTREPOT, "Entrepôt Douala " + MARQUEUR, "Douala");
        bertoua = lieu(TypeLieu.POINT_TRANSIT, "Transit Bertoua " + MARQUEUR, "Bertoua");
        garouaBoulai = lieu(TypeLieu.POINT_TRANSIT, "Transit Garoua-Boulaï " + MARQUEUR,
                "Garoua-Boulaï");
        bangui = lieu(TypeLieu.POINT_RECUPERATION, "Bangui PK5 " + MARQUEUR, "Bangui");
        bangui2 = lieu(TypeLieu.POINT_RECUPERATION, "Bangui Combattant " + MARQUEUR, "Bangui");
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("""
                DELETE FROM etape_itineraire WHERE itineraire_id IN (
                    SELECT id FROM itineraire WHERE nom LIKE ?)
                """, "%" + MARQUEUR + "%");
        jdbc.update("DELETE FROM itineraire WHERE nom LIKE ?", "%" + MARQUEUR + "%");
        jdbc.update("DELETE FROM lieu WHERE nom LIKE ?", "%" + MARQUEUR + "%");
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un trajet se lit avec des noms de lieux, pas des numéros")
    void trajetNomme() {
        VueItineraire cree = itineraires.creer("Axe principal " + MARQUEUR, douala, bangui,
                List.of(new Itineraire.EtapeSouhaitee(bertoua, 8),
                        new Itineraire.EtapeSouhaitee(garouaBoulai, 6)));

        // « 3 → 7 → 12 » obligerait à ouvrir chaque ligne pour savoir de quel
        // trajet il s'agit.
        assertThat(cree.lieuDepart()).contains("Douala");
        assertThat(cree.lieuArrivee()).contains("Bangui");
        assertThat(cree.etapes()).extracting(VueItineraire.VueEtape::lieu)
                .containsExactly("Transit Bertoua " + MARQUEUR,
                        "Transit Garoua-Boulaï " + MARQUEUR);

        // Les rangs sont ENGENDRÉS : 1, 2, … et jamais saisis.
        assertThat(cree.etapes()).extracting(VueItineraire.VueEtape::ordre)
                .containsExactly(1, 2);

        assertThat(cree.dureeTotaleHeures()).isEqualTo(14);

        // La lecture exécute réellement le JOIN FETCH : une @Query invalide
        // n'échouerait qu'ici, pas au démarrage.
        assertThat(itineraires.detail(cree.id()).etapes()).hasSize(2);
        assertThat(itineraires.lister(false)).isNotEmpty();
    }

    @Test
    @DisplayName("une durée partielle n'annonce aucun délai")
    void dureePartielle() {
        VueItineraire cree = itineraires.creer("Délai inconnu " + MARQUEUR, douala, bangui,
                List.of(new Itineraire.EtapeSouhaitee(bertoua, 8),
                        new Itineraire.EtapeSouhaitee(garouaBoulai, null)));

        // « 8 h » pour un trajet dont un tronçon sur deux est chiffré
        // annoncerait un délai que personne ne tiendrait.
        assertThat(cree.dureeTotaleHeures()).isNull();
    }

    @Test
    @DisplayName("réordonner les étapes ne heurte pas la contrainte de rang")
    void reordonner() {
        VueItineraire cree = itineraires.creer("À réordonner " + MARQUEUR, douala, bangui,
                List.of(new Itineraire.EtapeSouhaitee(bertoua, 8),
                        new Itineraire.EtapeSouhaitee(garouaBoulai, 6)));

        // 🎯 LE TEST QUI JUSTIFIE TOUTE LA CONCEPTION DU SERVICE.
        //
        // Inverser les deux étapes réutilise les rangs 1 et 2. Modifier rang
        // par rang traverserait un état où deux étapes portent le même rang, et
        // I-34 le refuse même s'il devait disparaître à la ligne suivante.
        // C'est pour ça que le service vide, FLUSH, puis réinsère.
        VueItineraire modifie = itineraires.modifier(cree.id(), "Inversé " + MARQUEUR,
                douala, bangui,
                List.of(new Itineraire.EtapeSouhaitee(garouaBoulai, 6),
                        new Itineraire.EtapeSouhaitee(bertoua, 8)));

        assertThat(modifie.etapes()).extracting(VueItineraire.VueEtape::lieu)
                .containsExactly("Transit Garoua-Boulaï " + MARQUEUR,
                        "Transit Bertoua " + MARQUEUR);
        assertThat(modifie.etapes()).extracting(VueItineraire.VueEtape::ordre)
                .containsExactly(1, 2);

        // Les anciennes étapes sont SUPPRIMÉES, pas laissées orphelines :
        // sinon la contrainte d'unicité continuerait à les voir.
        Integer restantes = jdbc.queryForObject(
                "SELECT count(*) FROM etape_itineraire WHERE itineraire_id = ?",
                Integer.class, cree.id());
        assertThat(restantes).isEqualTo(2);
    }

    @Test
    @DisplayName("un trajet direct est légitime")
    void trajetDirect() {
        VueItineraire direct = itineraires.creer("Direct " + MARQUEUR, douala, bangui, List.of());

        assertThat(direct.etapes()).isEmpty();
        // LEFT JOIN FETCH : une jointure interne le ferait disparaître de la
        // liste.
        assertThat(itineraires.lister(false)).extracting(VueItineraire::id)
                .contains(direct.id());
    }

    @Test
    @DisplayName("les extrémités et les étapes se tiennent")
    void trajetsRefuses() {
        assertThatThrownBy(() -> itineraires.creer("Boucle " + MARQUEUR, douala, douala, List.of()))
                .isInstanceOf(RegleMetierViolee.class);

        // GARAH ne livre pas à domicile (D-05) : on arrive forcément à un
        // point de récupération.
        assertThatThrownBy(() ->
                itineraires.creer("Vers un entrepôt " + MARQUEUR, bangui, douala, List.of()))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("point de récupération");

        // Un point de récupération ne sert pas de départ à un trajet type.
        assertThatThrownBy(() ->
                itineraires.creer("Retrait vers retrait " + MARQUEUR, bangui, bangui2, List.of()))
                .isInstanceOf(RegleMetierViolee.class);

        assertThatThrownBy(() -> itineraires.creer("Étape redondante " + MARQUEUR, douala, bangui,
                List.of(new Itineraire.EtapeSouhaitee(bangui, 4))))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("déjà le départ ou l'arrivée");

        assertThatThrownBy(() -> itineraires.creer("Étape doublée " + MARQUEUR, douala, bangui,
                List.of(new Itineraire.EtapeSouhaitee(bertoua, 4),
                        new Itineraire.EtapeSouhaitee(bertoua, 4))))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("deux fois");

        assertThatThrownBy(() -> itineraires.creer("Durée nulle " + MARQUEUR, douala, bangui,
                List.of(new Itineraire.EtapeSouhaitee(bertoua, 0))))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("on désactive un trajet, on ne le supprime pas")
    void desactivation() {
        VueItineraire cree = itineraires.creer("À retirer " + MARQUEUR, douala, bangui, List.of());

        VueItineraire retire = itineraires.activer(cree.id(), false);
        assertThat(retire.statut()).isEqualTo("INACTIF");

        // Un formulaire d'expédition ne propose que les trajets utilisables ;
        // le back-office, lui, doit continuer à voir ceux qu'on a retirés —
        // des expéditions passées les référencent.
        assertThat(itineraires.lister(true)).extracting(VueItineraire::id)
                .doesNotContain(cree.id());
        assertThat(itineraires.lister(false)).extracting(VueItineraire::id)
                .contains(cree.id());

        assertThat(itineraires.activer(cree.id(), true).statut()).isEqualTo("ACTIF");
    }

    // -------------------------------------------------------------------------

    private Long lieu(TypeLieu type, String nom, String ville) {
        String pays = type == TypeLieu.POINT_RECUPERATION ? "RCA" : "Cameroun";
        return lieux.save(new Lieu(type, nom, pays, ville)).getId();
    }
}
