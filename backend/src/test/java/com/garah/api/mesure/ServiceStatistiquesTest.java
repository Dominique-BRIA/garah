package com.garah.api.mesure;

import com.garah.api.catalogue.domaine.CategorieProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.mesure.domaine.BilanPeriode;
import com.garah.api.mesure.domaine.ProduitTendance;
import com.garah.api.mesure.domaine.ServiceStatistiques;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("Mesure et statistiques")
class ServiceStatistiquesTest {

    private static final String CODE_MARCHAND = "M-STAT-1";
    private static final String EMAIL = "client.stat@garah.cm";

    @Autowired ServiceStatistiques stats;
    @Autowired ServiceCatalogue catalogue;
    @Autowired CategorieProduitRepository categories;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private Long clientId;
    private Long produitA;
    private Long produitB;

    @BeforeEach
    void preparer() {
        nettoyer();

        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.CLIENT, "Tchoumi", EMAIL, "x"));
            u.marquerEmailVerifie();
            clientId = clients.save(new Client(u, "CLI-STAT-1")).getId();

            Long marchandId = jdbc.queryForObject("""
                    INSERT INTO marchand (code, nom, type)
                    VALUES (?, 'Marchand stats', 'EXTERNE') RETURNING id
                    """, Long.class, CODE_MARCHAND);
            Long categorieId = categories.save(new CategorieProduit("Statistiques", null)).getId();

            produitA = catalogue.creerProduit(marchandId, categorieId,
                    "REF-STAT-A", "Produit qui décolle", null).id();
            produitB = catalogue.creerProduit(marchandId, categorieId,
                    "REF-STAT-B", "Produit stable", null).id();
        });
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM journee_resumee WHERE jour >= CURRENT_DATE - 120");
        jdbc.update("DELETE FROM statistique_produit_jour WHERE produit_id IN (SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM vue_produit WHERE produit_id IN (SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM favori WHERE produit_id IN (SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM variante WHERE produit_id IN (SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM produit WHERE marchand_id IN (SELECT id FROM marchand WHERE code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM marchand WHERE code = ?", CODE_MARCHAND);
        jdbc.update("DELETE FROM categorie_produit WHERE nom = 'Statistiques'");
        jdbc.update("DELETE FROM client WHERE code_client = 'CLI-STAT-1'");
        jdbc.update("DELETE FROM utilisateur WHERE email = ?", EMAIL);
    }

    /** Insère une ligne d'agrégat directement : simule des jours passés. */
    private void agregat(Long produitId, LocalDate jour, int vues, int quantiteVendue) {
        jdbc.update("""
                INSERT INTO statistique_produit_jour
                    (produit_id, jour, vues, vues_uniques, quantite_vendue, chiffre_affaires)
                VALUES (?, ?, ?, ?, ?, 0)
                """, produitId, java.sql.Date.valueOf(jour), vues, vues, quantiteVendue);
        // La journee est notee comme resumee, comme le fait le vrai resume :
        // c'est elle que le bilan compte, pas les lignes par produit.
        jdbc.update("INSERT INTO journee_resumee (jour) VALUES (?) ON CONFLICT (jour) DO NOTHING",
                java.sql.Date.valueOf(jour));
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le bilan additionne les agrégats, jamais le détail")
    void bilanSurPeriode() {
        LocalDate hier = LocalDate.now().minusDays(1);
        LocalDate avantHier = LocalDate.now().minusDays(2);

        agregat(produitA, hier, 100, 10);
        agregat(produitA, avantHier, 50, 5);
        agregat(produitB, hier, 20, 1);

        // Ce test exécute réellement les trois requêtes du bilan — totaux,
        // classement, courbe. Une requête cassée n'échouerait qu'ici.
        BilanPeriode bilan = stats.bilan(avantHier, hier, 10);

        assertThat(bilan.vues()).isEqualTo(170);
        assertThat(bilan.quantiteVendue()).isEqualTo(16);

        // 🎯 `jours` compte les jours COUVERTS PAR DES AGRÉGATS, pas la
        //    longueur de la période. S'ils diffèrent, une nuit d'agrégation a
        //    été manquée — et c'est ce que l'écran doit pouvoir dire au lieu
        //    d'afficher un creux inexpliqué.
        assertThat(bilan.jours()).isEqualTo(2);

        assertThat(bilan.parJour()).hasSize(2);
        assertThat(bilan.parJour().getFirst().jour()).isEqualTo(avantHier);

        assertThat(bilan.meilleurs()).extracting(BilanPeriode.LigneBilan::produitId)
                .contains(produitA, produitB);
    }

    @Test
    @DisplayName("un produit sans aucune vue n'a pas de taux de conversion")
    void tauxSansVue() {
        LocalDate hier = LocalDate.now().minusDays(1);

        // Vendu sans avoir été vu : le cas arrive quand la vue n'a pas été
        // enregistrée (visiteur avec bloqueur, panne de la collecte).
        jdbc.update("""
                INSERT INTO statistique_produit_jour
                    (produit_id, jour, vues, vues_uniques, commandes, quantite_vendue,
                     chiffre_affaires)
                VALUES (?, ?, 0, 0, 3, 3, 45000)
                """, produitA, java.sql.Date.valueOf(hier));

        BilanPeriode.LigneBilan ligne = stats.bilan(hier, hier, 10).meilleurs().stream()
                .filter(l -> l.produitId().equals(produitA))
                .findFirst()
                .orElseThrow();

        // Nul, et non zéro : « 0 % » accuserait une fiche que personne n'a
        // ouverte, alors que le problème est ailleurs.
        assertThat(ligne.tauxConversion()).isNull();
        assertThat(ligne.commandes()).isEqualTo(3);
    }

    @Test
    @DisplayName("une période sans agrégat rend des zéros, pas une erreur")
    void periodeVide() {
        LocalDate vieux = LocalDate.now().minusYears(5);

        BilanPeriode bilan = stats.bilan(vieux, vieux.plusDays(7), 10);

        // Un écran de statistiques doit savoir dire « rien sur cette période ».
        // Lever une erreur ferait croire à une panne.
        assertThat(bilan.jours()).isZero();
        assertThat(bilan.vues()).isZero();
        assertThat(bilan.chiffreAffaires()).isEqualByComparingTo("0");
        assertThat(bilan.meilleurs()).isEmpty();
        assertThat(bilan.parJour()).isEmpty();
    }

    @Test
    @DisplayName("une période à l'envers est refusée")
    void periodeInversee() {
        LocalDate hier = LocalDate.now().minusDays(1);

        assertThatThrownBy(() -> stats.bilan(LocalDate.now(), hier, 10))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("une vue est enregistrée telle quelle")
    void collecteDesVues() {
        stats.enregistrerVue(produitA, clientId, "sess-1", "RECHERCHE", "41.202.0.1");
        stats.enregistrerVue(produitA, null, "sess-2", "CATEGORIE", "41.202.0.2");

        Long lignes = jdbc.queryForObject(
                "SELECT count(*) FROM vue_produit WHERE produit_id = ?", Long.class, produitA);
        assertThat(lignes).isEqualTo(2);
    }

    @Test
    @DisplayName("un visiteur non connecté est compté aussi")
    void visiteurAnonyme() {
        // La vitrine est publique, même si commander exige un compte (D-07).
        // Ne compter que les connectés fausserait toutes les mesures d'audience.
        stats.enregistrerVue(produitA, null, "sess-anonyme", "LIEN_DIRECT", null);

        Long anonymes = jdbc.queryForObject("""
                SELECT count(*) FROM vue_produit WHERE produit_id = ? AND client_id IS NULL
                """, Long.class, produitA);
        assertThat(anonymes).isEqualTo(1);
    }

    @Test
    @DisplayName("un favori ne se met qu'une fois")
    void favoriIdempotent() {
        stats.ajouterFavori(clientId, produitA);
        stats.ajouterFavori(clientId, produitA);

        assertThat(stats.favorisDe(produitA)).isEqualTo(1);

        stats.retirerFavori(clientId, produitA);
        assertThat(stats.favorisDe(produitA)).isZero();
    }

    @Test
    @DisplayName("l'agrégation distingue les vues des vues UNIQUES")
    void vuesUniques() {
        // Le même visiteur rafraîchit trois fois.
        stats.enregistrerVue(produitA, null, "sess-1", "RECHERCHE", null);
        stats.enregistrerVue(produitA, null, "sess-1", "RECHERCHE", null);
        stats.enregistrerVue(produitA, null, "sess-1", "RECHERCHE", null);
        // Un autre visiteur.
        stats.enregistrerVue(produitA, null, "sess-2", "RECHERCHE", null);

        stats.agregerLeJour(LocalDate.now());

        // Sans la distinction, un client qui rafraîchit dix fois compterait
        // pour dix visiteurs — et le classement des tendances serait faux.
        Integer vues = jdbc.queryForObject("""
                SELECT vues FROM statistique_produit_jour WHERE produit_id = ? AND jour = CURRENT_DATE
                """, Integer.class, produitA);
        Integer uniques = jdbc.queryForObject("""
                SELECT vues_uniques FROM statistique_produit_jour WHERE produit_id = ? AND jour = CURRENT_DATE
                """, Integer.class, produitA);

        assertThat(vues).isEqualTo(4);
        assertThat(uniques).isEqualTo(2);
    }

    @Test
    @DisplayName("relancer l'agrégation recalcule au lieu de dupliquer")
    void agregationIdempotente() {
        stats.enregistrerVue(produitA, null, "sess-1", "RECHERCHE", null);

        stats.agregerLeJour(LocalDate.now());
        stats.agregerLeJour(LocalDate.now());
        stats.agregerLeJour(LocalDate.now());

        // Un travail nocturne échoue parfois à mi-parcours, et on le relance.
        // ON CONFLICT DO UPDATE rend l'opération rejouable sans dégât.
        Long lignes = jdbc.queryForObject("""
                SELECT count(*) FROM statistique_produit_jour WHERE produit_id = ?
                """, Long.class, produitA);
        assertThat(lignes).isEqualTo(1);
    }

    @Test
    @DisplayName("les tendances classent par PROGRESSION, pas par volume")
    void tendancesParProgression() {
        LocalDate aujourdhui = LocalDate.now();

        // Produit A : décolle. 2 la semaine passée, 30 cette semaine.
        agregat(produitA, aujourdhui.minusDays(10), 50, 2);
        agregat(produitA, aujourdhui.minusDays(2), 400, 30);

        // Produit B : gros volume, mais stable.
        agregat(produitB, aujourdhui.minusDays(10), 500, 40);
        agregat(produitB, aujourdhui.minusDays(2), 500, 40);

        List<ProduitTendance> tendances = stats.produitsTendance(10);

        ProduitTendance a = tendances.stream()
                .filter(t -> t.produitId().equals(produitA)).findFirst().orElseThrow();
        ProduitTendance b = tendances.stream()
                .filter(t -> t.produitId().equals(produitB)).findFirst().orElseThrow();

        // B vend plus en absolu, mais A progresse : 30/2 = 15 contre 40/40 = 1.
        // Classer par volume laisserait le même best-seller en tête pendant
        // trois ans.
        assertThat(a.croissance()).isGreaterThan(b.croissance());
        assertThat(a.ventesRecentes()).isEqualTo(30);
        assertThat(a.ventesPrecedentes()).isEqualTo(2);
    }

    @Test
    @DisplayName("une progression depuis zéro est bornée")
    void progressionDepuisZero() {
        agregat(produitA, LocalDate.now().minusDays(1), 10, 1);

        ProduitTendance a = stats.produitsTendance(10).stream()
                .filter(t -> t.produitId().equals(produitA)).findFirst().orElseThrow();

        // Sinon un produit vendu UNE fois aurait une croissance infinie et
        // écraserait tout le classement.
        assertThat(a.croissance()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("l'agrégat survit à la purge du détail")
    void purgeConserveLAgregat() {
        stats.enregistrerVue(produitA, null, "sess-1", "RECHERCHE", null);
        stats.agregerLeJour(LocalDate.now());

        // On vieillit artificiellement la vue au-delà de la rétention.
        jdbc.update("""
                UPDATE vue_produit SET date_heure = now() - interval '100 days' WHERE produit_id = ?
                """, produitA);

        assertThat(stats.purgerLeDetail()).isGreaterThanOrEqualTo(1);

        // Le détail disparaît, la statistique reste. C'est tout l'intérêt de
        // D-15 : on garde ce qui sert, on jette ce qui pèse.
        assertThat(stats.vuesTotales(produitA)).isEqualTo(1);
        Long detail = jdbc.queryForObject(
                "SELECT count(*) FROM vue_produit WHERE produit_id = ?", Long.class, produitA);
        assertThat(detail).isZero();
    }

    @Test
    @DisplayName("une erreur de mesure ne fait pas échouer l'affichage")
    void mesureNonBloquante() {
        // Produit inexistant : la clé étrangère refuse l'insertion.
        stats.enregistrerVue(-999L, null, "sess-x", "RECHERCHE", null);

        // Aucune exception : une statistique perdue est regrettable, une fiche
        // produit en erreur est un client perdu.
        assertThat(stats.vuesTotales(produitA)).isZero();
    }

    @Test
    @DisplayName("⚠️ une journée calme est résumée, et comptée comme telle")
    void uneJourneeCalmeEstResumee() {
        // 🎯 LE DEFAUT QUE CE TEST FERME.
        //
        //    Une journee sans visite ni vente ne laissait aucune ligne. L'ecran,
        //    qui comptait les jours a partir d'elles, affirmait « ces jours-la
        //    n'ont pas ete resumes » pour des jours simplement calmes.
        LocalDate calme = LocalDate.now().minusDays(50);
        jdbc.update("DELETE FROM journee_resumee WHERE jour = ?", java.sql.Date.valueOf(calme));

        stats.agregerLeJour(calme);

        BilanPeriode bilan = stats.bilan(calme, calme, 10);
        assertThat(bilan.jours()).as("la journee est comptee comme resumee").isEqualTo(1);
        assertThat(bilan.parJour()).singleElement()
                .satisfies(j -> assertThat(j.vues()).isZero());
    }

    @Test
    @DisplayName("le rattrapage résume toutes les nuits manquées, une seule fois")
    void rattrapageDesNuitsManquees() {
        LocalDate du = LocalDate.now().minusDays(40);
        LocalDate au = LocalDate.now().minusDays(30);
        jdbc.update("DELETE FROM journee_resumee WHERE jour BETWEEN ? AND ?",
                java.sql.Date.valueOf(du), java.sql.Date.valueOf(au));

        assertThat(stats.rattraper(du, au)).isEqualTo(11);
        assertThat(stats.bilan(du, au, 10).jours()).isEqualTo(11);

        // Rejoue : rien de plus. Seules les journees JAMAIS resumees le sont.
        assertThat(stats.rattraper(du, au)).isZero();
    }

    @Test
    @DisplayName("⚠️ le rattrapage ne touche ni aujourd'hui, ni ce qui dépasse la rétention des vues")
    void rattrapageBorne() {
        // Au-dela de 90 jours, le detail des vues est purge : resumer ces jours
        // afficherait zero visite la ou il y en a eu. Et aujourd'hui n'est pas fini.
        LocalDate trop = LocalDate.now().minusDays(100);
        assertThat(stats.rattraper(trop, trop.plusDays(5))).isZero();

        LocalDate aujourdhui = LocalDate.now(java.time.ZoneId.of("Africa/Douala"));
        jdbc.update("DELETE FROM journee_resumee WHERE jour = ?", java.sql.Date.valueOf(aujourdhui));
        stats.rattraper(aujourdhui, aujourdhui);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journee_resumee WHERE jour = ?",
                Long.class, java.sql.Date.valueOf(aujourdhui))).isZero();
    }
}
