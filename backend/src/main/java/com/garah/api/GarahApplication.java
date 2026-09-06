package com.garah.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entrée de l'API GARAH.
 *
 * <p>Au démarrage, Flyway applique automatiquement les migrations manquantes
 * de {@code db/migration}, puis Hibernate se connecte à un schéma qu'il n'a
 * pas créé et qu'il ne modifiera jamais ({@code ddl-auto: none}).</p>
 */
@SpringBootApplication
public class GarahApplication {

    public static void main(String[] args) {
        SpringApplication.run(GarahApplication.class, args);
    }
}
