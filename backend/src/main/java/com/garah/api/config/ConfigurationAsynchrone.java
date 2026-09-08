package com.garah.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Active les traitements détachés.
 *
 * <h2>⚠️ Sans {@code @EnableAsync}, {@code @Async} est ignoré EN SILENCE</h2>
 *
 * <p>Les méthodes annotées s'exécutent alors <b>dans le fil appelant</b>, comme
 * si de rien n'était. Rien ne le signale : ça compile, ça démarre, ça marche —
 * et l'envoi d'une notification, qui devait suivre la transaction, se met à la
 * bloquer.</p>
 *
 * <p>C'est le même piège que {@code @EnableScheduling}, et il se manifeste
 * exactement là où on ne regarde pas : un aller-retour vers Google ajouté à
 * une remise de marchandise.</p>
 *
 * <h2>À quoi ça sert ici</h2>
 *
 * <p>Aux notifications, et à elles seules. Elles partent vers un service
 * distant : les faire porter par la transaction métier ferait attendre — et
 * parfois échouer — un geste qui n'a rien à voir.</p>
 */
@Configuration
@EnableAsync
public class ConfigurationAsynchrone {

    /**
     * Un pool <b>petit et borné</b>, avec une file bornée elle aussi.
     *
     * <p>⚠️ Le défaut de Spring ({@code SimpleAsyncTaskExecutor}) crée un fil
     * <b>par appel</b>, sans aucune limite. Une rafale de notifications — un
     * envoi à toute l'équipe, par exemple — créerait autant de fils que de
     * destinataires, et sur l'instance modeste d'Azure cela se termine par un
     * serveur qui ne répond plus.</p>
     *
     * <p>Deux fils : ces tâches attendent le réseau, elles ne calculent rien.
     * Et la file est bornée à 500 — au-delà, on préfère <b>perdre</b> des
     * notifications que de gonfler la mémoire jusqu'à l'arrêt. Une
     * notification perdue est regrettable ; un serveur mort l'est plus.</p>
     */
    @Bean(name = "executeurNotifications")
    public Executor executeurNotifications() {
        ThreadPoolTaskExecutor executeur = new ThreadPoolTaskExecutor();
        executeur.setCorePoolSize(2);
        executeur.setMaxPoolSize(4);
        executeur.setQueueCapacity(500);
        executeur.setThreadNamePrefix("garah-notif-");

        // ⚠️ La politique de rejet est DISCARD, pas ABORT : une file pleine ne
        //    doit pas remonter une exception dans le fil appelant, qui vient
        //    justement de réussir une transaction.
        executeur.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());

        // Laisse partir ce qui est en cours à l'arrêt : Azure envoie un SIGTERM
        // avant de couper. Sans cela, la notification de la marchandise arrivée
        // se perdrait au redéploiement.
        executeur.setWaitForTasksToCompleteOnShutdown(true);
        executeur.setAwaitTerminationSeconds(15);

        executeur.initialize();
        return executeur;
    }
}
