package com.garah.api.stock;

import com.garah.api.catalogue.domaine.CategorieProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.stock.domaine.EtatStock;
import com.garah.api.stock.domaine.ServiceStock;
import com.garah.api.stock.domaine.VueMouvement;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("Stock")
class ServiceStockTest {

    @Autowired ServiceStock stock;
    @Autowired ServiceCatalogue catalogue;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long varianteId;

    @BeforeEach
    void preparer() {
        Long marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type) VALUES ('M-STK-1', 'Marchand stock', 'EXTERNE')
                RETURNING id
                """, Long.class);
        Long categorieId = categories.save(new CategorieProduit("Stock de test", null)).getId();
        Long produitId = catalogue.creerProduit(marchandId, categorieId, "REF-STK-1",
                "Produit stocké", null).id();
        em.flush();

        varianteId = variantes.findByProduitId(produitId).getFirst().getId();
        stock.creerPour(varianteId);
        stock.entrer(varianteId, 10, null, "Réception initiale");
        em.flush();
    }

    @Test
    @DisplayName("une réception augmente le disponible")
    void reception() {
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(10);
        assertThat(stock.etat(varianteId).total()).isEqualTo(10);
    }

    @Test
    @DisplayName("réserver déplace du disponible vers le réservé, sans rien perdre")
    void reservation() {
        EtatStock apres = stock.reserver(varianteId, 3, 42L);

        assertThat(apres.disponible()).isEqualTo(7);
        assertThat(apres.reserve()).isEqualTo(3);
        // Le total physique ne bouge PAS : rien n'a quitté l'entrepôt.
        assertThat(apres.total()).isEqualTo(10);
    }

    @Test
    @DisplayName("une réservation écrit DEUX mouvements")
    void reservationEnDoubleEntree() {
        stock.reserver(varianteId, 3, 42L);
        em.flush();

        Long lignes = jdbc.queryForObject("""
                SELECT count(*) FROM mouvement_stock m
                  JOIN stock s ON s.id = m.stock_id
                 WHERE s.variante_id = ? AND m.type = 'RESERVATION'
                """, Long.class, varianteId);

        // Une pour DISPONIBLE (-3), une pour RESERVEE (+3). Comme une écriture
        // comptable : chaque compteur a sa propre ligne, et l'état est la
        // somme de son journal.
        assertThat(lignes).isEqualTo(2);
    }

    @Test
    @DisplayName("on ne peut pas réserver plus que disponible")
    void reservationImpossible() {
        assertThatThrownBy(() -> stock.reserver(varianteId, 11, 42L))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("libérer une réservation rend la marchandise vendable")
    void liberation() {
        stock.reserver(varianteId, 4, 42L);
        EtatStock apres = stock.liberer(varianteId, 4, 42L);

        assertThat(apres.disponible()).isEqualTo(10);
        assertThat(apres.reserve()).isZero();
    }

    @Test
    @DisplayName("la sortie ne touche que le réservé : le disponible a déjà baissé")
    void sortie() {
        stock.reserver(varianteId, 4, 42L);
        EtatStock apres = stock.confirmerSortie(varianteId, 4, 42L);

        assertThat(apres.disponible()).isEqualTo(6);
        assertThat(apres.reserve()).isZero();
        // Cette fois le total physique diminue : la marchandise est partie.
        assertThat(apres.total()).isEqualTo(6);
    }

    @Test
    @DisplayName("un retour en bon état redevient vendable, un retour abîmé non")
    void retours() {
        EtatStock bon = stock.retour(varianteId, 2, 7L, true);
        assertThat(bon.disponible()).isEqualTo(12);

        EtatStock abime = stock.retour(varianteId, 1, 8L, false);
        assertThat(abime.disponible()).isEqualTo(12);      // inchangé
        assertThat(abime.endommage()).isEqualTo(1);
        // L'article existe physiquement mais ne doit jamais être revendu.
        assertThat(abime.total()).isEqualTo(13);
    }

    @Test
    @DisplayName("un ajustement sans motif est refusé")
    void ajustementSansMotif() {
        assertThatThrownBy(() -> stock.ajuster(varianteId, 8, null, "  "))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("justifié");
    }

    @Test
    @DisplayName("un ajustement corrige le disponible et laisse une trace")
    void ajustement() {
        EtatStock apres = stock.ajuster(varianteId, 8, null, "Inventaire du 6 septembre : 2 unités manquantes");
        em.flush();

        assertThat(apres.disponible()).isEqualTo(8);

        String commentaire = jdbc.queryForObject("""
                SELECT m.commentaire FROM mouvement_stock m
                  JOIN stock s ON s.id = m.stock_id
                 WHERE s.variante_id = ? AND m.type = 'AJUSTEMENT'
                """, String.class, varianteId);
        assertThat(commentaire).contains("Inventaire");
    }

    @Test
    @DisplayName("un ajustement sans écart n'écrit aucun mouvement")
    void ajustementSansEcart() {
        stock.ajuster(varianteId, 10, null, "Inventaire conforme");
        em.flush();

        Long ajustements = jdbc.queryForObject("""
                SELECT count(*) FROM mouvement_stock m
                  JOIN stock s ON s.id = m.stock_id
                 WHERE s.variante_id = ? AND m.type = 'AJUSTEMENT'
                """, Long.class, varianteId);

        // Un mouvement de zéro est interdit par la contrainte
        // mouvement_stock_quantite_non_nulle — et il n'apprendrait rien.
        assertThat(ajustements).isZero();
    }

    @Test
    @DisplayName("l'état stocké correspond toujours à la somme du journal")
    void reconciliation() {
        stock.reserver(varianteId, 3, 42L);
        stock.confirmerSortie(varianteId, 2, 42L);
        stock.liberer(varianteId, 1, 42L);
        stock.retour(varianteId, 1, 7L, false);
        em.flush();

        // C'est le controle a faire tourner chaque nuit : si l'etat et le
        // journal divergent, une ecriture a eu lieu hors du service.
        assertThat(stock.estReconcilie(varianteId)).isTrue();
    }

    // -------------------------------------------------------------------------
    // La liste du back-office
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("la liste sans recherche passe une collection NULLE au IN")
    void listeSansRecherche() {
        em.flush();

        // ⚠️ Ce test existe pour UNE raison : la requete porte
        // « :varianteIds IS NULL OR s.varianteId IN :varianteIds », et un
        // parametre de collection nul est exactement le genre de chose qui
        // compile, demarre, et casse a la premiere execution. Le verifier au
        // demarrage ne suffit pas — il faut l'appeler.
        Page<EtatStock> page = stock.administration(null, false, PageRequest.of(0, 25));

        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent().getFirst().varianteId()).isEqualTo(varianteId);
    }

    @Test
    @DisplayName("la liste porte la designation venue du catalogue")
    void listeDesignee() {
        em.flush();

        EtatStock etat = stock.administration(null, false, PageRequest.of(0, 25))
                .getContent().getFirst();

        // Sans cela, l'ecran afficherait « variante 42 : 10 disponibles » —
        // une ligne que personne ne sait interpreter.
        assertThat(etat.produitNom()).isEqualTo("Produit stocké");
        assertThat(etat.sku()).isNotBlank();
    }

    @Test
    @DisplayName("la recherche passe par le catalogue, puis filtre les stocks")
    void listeRecherchee() {
        em.flush();

        // Le stock ne connait que des identifiants : il ne sait pas ce qu'est
        // un « produit stocké ». La recherche interroge donc le catalogue en
        // premier.
        assertThat(stock.administration("stocké", false, PageRequest.of(0, 25))).isNotEmpty();

        // Et quand rien ne correspond, on ne lance meme pas la seconde requete :
        // `IN ()` est invalide en SQL.
        assertThat(stock.administration("introuvable-xyz", false, PageRequest.of(0, 25)))
                .isEmpty();
    }

    @Test
    @DisplayName("l'historique explique la quantite courante")
    void historique() {
        stock.entrer(varianteId, 5, null, "Second arrivage");
        em.flush();

        List<VueMouvement> journal = stock.mouvements(varianteId);

        // Le plus recent d'abord : c'est ce qu'on cherche quand on ouvre
        // l'historique apres avoir vu un chiffre surprenant.
        assertThat(journal).hasSize(2);
        assertThat(journal.getFirst().commentaire()).isEqualTo("Second arrivage");
        assertThat(journal.getFirst().quantiteAvant()).isEqualTo(10);
        assertThat(journal.getFirst().quantiteApres()).isEqualTo(15);
    }
}
