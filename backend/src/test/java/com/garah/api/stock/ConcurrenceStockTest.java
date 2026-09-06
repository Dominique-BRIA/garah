package com.garah.api.stock;

import com.garah.api.catalogue.domaine.CategorieProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.stock.domaine.EtatStock;
import com.garah.api.stock.domaine.ServiceStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le test le plus important du module stock.
 *
 * <p>Il reproduit le scénario du chapitre 05 : <b>deux clients achètent le
 * dernier article à la même milliseconde</b>. C'est un bug qui ne se voit
 * jamais en développement — on est seul — et qui apparaît le jour d'une
 * promotion.</p>
 *
 * <p>⚠️ Cette classe n'est délibérément <b>pas</b> {@code @Transactional} :
 * une transaction de test unique annulée à la fin rendrait la concurrence
 * impossible à observer, puisque les threads ne verraient jamais les
 * écritures des autres. On commit pour de vrai, et on nettoie ensuite.</p>
 */
@SpringBootTest
@DisplayName("Stock sous concurrence")
class ConcurrenceStockTest {

    @Autowired ServiceStock stock;
    @Autowired ServiceCatalogue catalogue;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private static final String CODE_MARCHAND = "M-CONC-1";

    /** Prépare un stock committé, avec exactement {@code quantite} unités. */
    private Long preparerStock(int quantite) {
        return transactions.execute(statut -> {
            Long marchandId = jdbc.queryForObject("""
                    INSERT INTO marchand (code, nom, type)
                    VALUES (?, 'Marchand concurrence', 'EXTERNE') RETURNING id
                    """, Long.class, CODE_MARCHAND);

            Long categorieId = categories.save(new CategorieProduit("Concurrence", null)).getId();
            Long produitId = catalogue.creerProduit(marchandId, categorieId,
                    "REF-CONC-1", "Article rare", null).id();

            Long varianteId = variantes.findByProduitId(produitId).getFirst().getId();
            stock.creerPour(varianteId);
            stock.entrer(varianteId, quantite, null, "Mise en place du test");
            return varianteId;
        });
    }

    @AfterEach
    void nettoyer() {
        // L'ordre suit les clés étrangères, des feuilles vers la racine.
        jdbc.update("""
                DELETE FROM mouvement_stock WHERE stock_id IN (
                    SELECT s.id FROM stock s
                      JOIN variante v ON v.id = s.variante_id
                      JOIN produit p ON p.id = v.produit_id
                      JOIN marchand m ON m.id = p.marchand_id
                     WHERE m.code = ?)
                """, CODE_MARCHAND);
        jdbc.update("""
                DELETE FROM stock WHERE variante_id IN (
                    SELECT v.id FROM variante v
                      JOIN produit p ON p.id = v.produit_id
                      JOIN marchand m ON m.id = p.marchand_id
                     WHERE m.code = ?)
                """, CODE_MARCHAND);
        jdbc.update("""
                DELETE FROM variante WHERE produit_id IN (
                    SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id
                     WHERE m.code = ?)
                """, CODE_MARCHAND);
        jdbc.update("DELETE FROM produit WHERE marchand_id IN (SELECT id FROM marchand WHERE code = ?)",
                CODE_MARCHAND);
        jdbc.update("DELETE FROM marchand WHERE code = ?", CODE_MARCHAND);
        jdbc.update("DELETE FROM categorie_produit WHERE nom = 'Concurrence'");
    }

    @Test
    @DisplayName("deux clients, un seul article : exactement un gagne")
    void deuxClientsUnSeulArticle() throws Exception {
        Long varianteId = preparerStock(1);

        Resultat resultat = lancerEnParallele(varianteId, 2, 1);

        // Sans verrou, les DEUX passeraient : chacun lirait « il en reste 1 »
        // avant que l'autre n'écrive. L'article serait vendu deux fois, et
        // personne ne s'en apercevrait avant la préparation de commande.
        assertThat(resultat.succes()).isEqualTo(1);
        assertThat(resultat.refus()).isEqualTo(1);

        EtatStock etat = stock.etat(varianteId);
        assertThat(etat.disponible()).isZero();
        assertThat(etat.reserve()).isEqualTo(1);
        assertThat(etat.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("vingt clients, cinq articles : exactement cinq gagnent")
    void vingtClientsCinqArticles() throws Exception {
        Long varianteId = preparerStock(5);

        Resultat resultat = lancerEnParallele(varianteId, 20, 1);

        assertThat(resultat.succes()).isEqualTo(5);
        assertThat(resultat.refus()).isEqualTo(15);

        EtatStock etat = stock.etat(varianteId);
        assertThat(etat.disponible()).isZero();
        assertThat(etat.reserve()).isEqualTo(5);
    }

    @Test
    @DisplayName("le journal reste cohérent même après une bousculade")
    void journalCoherentApresConcurrence() throws Exception {
        Long varianteId = preparerStock(5);
        lancerEnParallele(varianteId, 20, 1);

        // Chaque réservation gagnante a écrit ses deux mouvements dans SA
        // transaction. Les perdantes n'ont rien écrit du tout.
        assertThat(stock.estReconcilie(varianteId)).isTrue();
    }

    // -------------------------------------------------------------------------

    private record Resultat(int succes, int refus) {
    }

    /**
     * Lance {@code clients} tentatives simultanées.
     *
     * <p>La {@link CyclicBarrier} est essentielle : sans elle, les threads
     * démarrent les uns après les autres et le test passerait même sans
     * verrou. Elle force le départ groupé, au même instant.</p>
     */
    private Resultat lancerEnParallele(Long varianteId, int clients, int quantite) throws Exception {
        AtomicInteger succes = new AtomicInteger();
        AtomicInteger refus = new AtomicInteger();
        CyclicBarrier depart = new CyclicBarrier(clients);
        CountDownLatch fini = new CountDownLatch(clients);

        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                long commandeId = 1000L + i;
                pool.submit(() -> {
                    try {
                        depart.await(10, TimeUnit.SECONDS);
                        stock.reserver(varianteId, quantite, commandeId);
                        succes.incrementAndGet();
                    } catch (ConflitEtat attendu) {
                        refus.incrementAndGet();
                    } catch (Exception inattendue) {
                        // Un interblocage ou un délai de verrou dépassé
                        // compterait ici : on ne veut ni l'un ni l'autre.
                        throw new IllegalStateException(inattendue);
                    } finally {
                        fini.countDown();
                    }
                });
            }

            assertThat(fini.await(30, TimeUnit.SECONDS))
                    .as("toutes les tentatives doivent se terminer sans interblocage")
                    .isTrue();
        }

        return new Resultat(succes.get(), refus.get());
    }
}
