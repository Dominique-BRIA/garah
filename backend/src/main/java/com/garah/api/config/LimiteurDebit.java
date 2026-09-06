package com.garah.api.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Un compteur de requêtes par clé, sur fenêtre glissante par blocs (D-24).
 *
 * <h2>Ce que ça protège</h2>
 *
 * <p>{@code POST /api/auth/inscription} crée un compte sans vérification
 * d'identité et coûte <b>250 ms de BCrypt</b> par appel. Sans limite, un
 * script crée dix mille comptes en une minute — et met au passage l'instance
 * Render gratuite à genoux, puisque 250 ms de CPU répétés saturent le seul
 * cœur disponible.</p>
 *
 * <p>{@code POST /api/auth/connexion} est l'autre cible évidente : sans limite,
 * un mot de passe se cherche à la vitesse du réseau.</p>
 *
 * <h2>⚠️ En mémoire, donc valable pour UNE instance</h2>
 *
 * <p>Le compteur vit dans le processus. Avec deux instances, chacune
 * autoriserait le quota complet — la limite serait doublée sans que rien ne le
 * signale. C'est acceptable aujourd'hui (Render gratuit, une seule instance,
 * D-14) et ça cesse de l'être à la seconde.</p>
 *
 * <p>Le jour de la mise à l'échelle, il faudra un compteur partagé (Redis, ou
 * une table PostgreSQL puisque la base est déjà là). <b>C'est le même
 * avertissement que pour les traitements périodiques (D-18)</b>, et il se paie
 * de la même façon : silencieusement.</p>
 *
 * <h2>Pourquoi pas un vrai seau à jetons</h2>
 *
 * <p>Un <i>token bucket</i> lisserait mieux les rafales. Il demande un calcul
 * de recharge à chaque appel et un état par clé plus lourd. Pour une poignée
 * de routes sensibles, un compteur par fenêtre suffit — et se relit sans
 * effort, ce qui compte pour une règle de sécurité.</p>
 */
public class LimiteurDebit {

    /**
     * Plafond du nombre de clés suivies.
     *
     * <p>🎯 <b>Sans ce plafond, le limiteur devient lui-même l'attaque.</b> Une
     * requête par adresse IP falsifiée ferait grossir la table indéfiniment
     * jusqu'à l'{@code OutOfMemoryError} — on aurait remplacé un déni de
     * service par un autre, en croyant se protéger.</p>
     */
    private static final int CLES_MAX = 50_000;

    private final int maximum;
    private final Duration fenetre;
    private final Map<String, Compteur> compteurs = new ConcurrentHashMap<>();

    public LimiteurDebit(int maximum, Duration fenetre) {
        this.maximum = maximum;
        this.fenetre = fenetre;
    }

    /** Une clé et son décompte sur la fenêtre en cours. */
    private static final class Compteur {
        final AtomicInteger vues = new AtomicInteger();
        volatile Instant debut = Instant.now();
    }

    /**
     * Enregistre une tentative.
     *
     * @return le nombre de secondes à attendre si la limite est atteinte,
     *         ou {@code 0} si l'appel est autorisé
     */
    public long tenter(String cle) {
        purgerSiNecessaire();

        Compteur compteur = compteurs.computeIfAbsent(cle, ignore -> new Compteur());

        synchronized (compteur) {
            Instant maintenant = Instant.now();

            // La fenêtre est écoulée : on repart de zéro.
            if (maintenant.isAfter(compteur.debut.plus(fenetre))) {
                compteur.debut = maintenant;
                compteur.vues.set(0);
            }

            if (compteur.vues.incrementAndGet() > maximum) {
                long reste = Duration.between(maintenant, compteur.debut.plus(fenetre)).toSeconds();
                return Math.max(reste, 1);
            }
            return 0;
        }
    }

    /**
     * Vide la table quand elle devient trop grande.
     *
     * <p>Volontairement brutal : on efface tout plutôt que de trier les entrées
     * périmées. Un balayage sélectif coûterait un parcours complet sous
     * verrou, dans le chemin critique d'une requête.</p>
     *
     * <p>Ce que ça coûte : au moment de la purge, les compteurs en cours sont
     * remis à zéro et quelques appels passent en trop. Ce que ça évite : que le
     * limiteur devienne le point de rupture qu'il était censé empêcher. Le
     * compromis est le bon — 50 000 clés distinctes signifient de toute façon
     * une attaque, pas un trafic normal.</p>
     */
    private void purgerSiNecessaire() {
        if (compteurs.size() > CLES_MAX) {
            compteurs.clear();
        }
    }

    /** Utile aux tests, et à un futur endpoint d'administration. */
    public int clesSuivies() {
        return compteurs.size();
    }
}
