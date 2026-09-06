package com.garah.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le limiteur de débit (D-24).
 *
 * <p>Test <b>unitaire pur</b> : aucun contexte Spring, aucune base. Le
 * limiteur est du calcul, et le vérifier ne demande rien d'autre — les 40
 * secondes de démarrage d'un {@code @SpringBootTest} seraient du gaspillage.</p>
 */
@DisplayName("Limitation de debit")
class LimiteurDebitTest {

    @Test
    @DisplayName("les appels sous le plafond passent")
    void sousLePlafondCaPasse() {
        LimiteurDebit limiteur = new LimiteurDebit(3, Duration.ofMinutes(5));

        assertThat(limiteur.tenter("1.2.3.4")).isZero();
        assertThat(limiteur.tenter("1.2.3.4")).isZero();
        assertThat(limiteur.tenter("1.2.3.4")).isZero();
    }

    @Test
    @DisplayName("l'appel de trop est refuse, avec un delai d'attente")
    void auDelaDuPlafondCaBloque() {
        LimiteurDebit limiteur = new LimiteurDebit(3, Duration.ofMinutes(5));

        limiteur.tenter("1.2.3.4");
        limiteur.tenter("1.2.3.4");
        limiteur.tenter("1.2.3.4");

        long attente = limiteur.tenter("1.2.3.4");

        // Un delai STRICTEMENT positif : c'est lui qui alimente Retry-After.
        // Sans en-tete, un frontend mal ecrit transforme le refus en
        // martelement, et la protection aggrave le probleme.
        assertThat(attente).isPositive().isLessThanOrEqualTo(300);
    }

    /**
     * 🎯 Le test qui compte le plus.
     *
     * <p>Le compteur est <b>par clé</b>. S'il était global, le premier inscrit
     * de la journée bloquerait tous les suivants — une protection qui devient
     * une panne.</p>
     */
    @Test
    @DisplayName("chaque adresse a son propre compteur")
    void lesClesSontIndependantes() {
        LimiteurDebit limiteur = new LimiteurDebit(2, Duration.ofMinutes(5));

        limiteur.tenter("1.1.1.1");
        limiteur.tenter("1.1.1.1");
        assertThat(limiteur.tenter("1.1.1.1")).isPositive();

        // Un autre visiteur n'est pas affecte par le premier.
        assertThat(limiteur.tenter("2.2.2.2")).isZero();
    }

    @Test
    @DisplayName("la fenetre ecoulee remet le compteur a zero")
    void laFenetreSeRenouvelle() throws InterruptedException {
        LimiteurDebit limiteur = new LimiteurDebit(1, Duration.ofMillis(120));

        assertThat(limiteur.tenter("1.2.3.4")).isZero();
        assertThat(limiteur.tenter("1.2.3.4")).isPositive();

        Thread.sleep(200);

        assertThat(limiteur.tenter("1.2.3.4"))
                .as("la fenetre est passee : on repart de zero")
                .isZero();
    }

    /**
     * ⚠️ Sous charge, le compteur ne doit ni sur-compter ni sous-compter.
     *
     * <p>Un limiteur qui laisse passer davantage sous concurrence ne protège
     * de rien précisément au moment où il servirait — une attaque n'envoie pas
     * ses requêtes une par une.</p>
     */
    @Test
    @DisplayName("le decompte reste juste sous concurrence")
    void leDecompteTientSousConcurrence() throws InterruptedException {
        int plafond = 50;
        int tentatives = 500;

        LimiteurDebit limiteur = new LimiteurDebit(plafond, Duration.ofMinutes(5));
        AtomicInteger acceptes = new AtomicInteger();

        CountDownLatch depart = new CountDownLatch(1);
        CountDownLatch fini = new CountDownLatch(tentatives);

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < tentatives; i++) {
                pool.submit(() -> {
                    try {
                        depart.await();
                        if (limiteur.tenter("1.2.3.4") == 0) {
                            acceptes.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        fini.countDown();
                    }
                });
            }

            depart.countDown();
            assertThat(fini.await(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(acceptes.get())
                .as("exactement le plafond doit passer, ni plus ni moins")
                .isEqualTo(plafond);
    }

    @Test
    @DisplayName("les cles distinctes sont bien comptees")
    void lesClesSontSuivies() {
        LimiteurDebit limiteur = new LimiteurDebit(5, Duration.ofMinutes(5));

        limiteur.tenter("1.1.1.1");
        limiteur.tenter("2.2.2.2");
        limiteur.tenter("1.1.1.1");

        assertThat(limiteur.clesSuivies()).isEqualTo(2);
    }
}
