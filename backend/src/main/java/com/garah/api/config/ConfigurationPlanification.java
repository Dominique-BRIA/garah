package com.garah.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Active les traitements périodiques.
 *
 * <p>Sans {@code @EnableScheduling}, toutes les annotations {@code @Scheduled}
 * du projet sont <b>ignorées en silence</b> : elles compilent, l'application
 * démarre, et rien ne s'exécute jamais. C'est précisément l'état dans lequel
 * était GARAH.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "garah.planification.active", havingValue = "true",
                       matchIfMissing = true)
public class ConfigurationPlanification {

    /**
     * Le planificateur, avec <b>deux</b> fils d'exécution.
     *
     * <p>⚠️ Par défaut, Spring n'en donne qu'<b>un seul</b>. Toutes les tâches
     * se mettent alors en file : si la réconciliation des paiements met
     * quarante secondes parce que Campay répond lentement, la libération du
     * stock attend. Pire, une tâche bloquée sur un appel réseau gèle
     * <b>toutes</b> les autres, indéfiniment.</p>
     *
     * <p>Deux fils, pas dix : ces traitements écrivent en base, et le pool
     * PostgreSQL n'a que 5 connexions sur Neon (D-14). Un planificateur
     * généreux viderait le pool et ferait échouer les requêtes des vrais
     * utilisateurs — on aurait réglé un problème invisible en en créant un
     * très visible.</p>
     */
    @Bean
    public ThreadPoolTaskScheduler planificateur() {
        ThreadPoolTaskScheduler planificateur = new ThreadPoolTaskScheduler();
        planificateur.setPoolSize(2);
        planificateur.setThreadNamePrefix("garah-tache-");

        // Laisse les tâches en cours se terminer à l'arrêt : Render envoie un
        // SIGTERM avant de couper. Sans cela, une agrégation interrompue en
        // plein milieu laisserait des statistiques partielles.
        planificateur.setWaitForTasksToCompleteOnShutdown(true);
        planificateur.setAwaitTerminationSeconds(20);

        return planificateur;
    }
}
