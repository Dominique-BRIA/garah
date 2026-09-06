package com.garah.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * Fige la forme JSON des pages.
 *
 * <p>Spring Data l'annonce lui-même au démarrage :</p>
 *
 * <pre>Serializing PageImpl instances as-is is not supported, meaning that there
 * is no guarantee about the stability of the resulting JSON structure!</pre>
 *
 * <p><b>Ce que ça veut dire concrètement.</b> Sans cette configuration, le JSON
 * renvoyé par {@code GET /api/produits} est le reflet direct des champs
 * internes de la classe {@code PageImpl}. Une montée de version de Spring Data
 * peut les renommer — et les trois frontends Angular cessent d'afficher le
 * catalogue, sans qu'aucun test backend ne devienne rouge.</p>
 *
 * <p>{@code VIA_DTO} impose une forme stable, contractuelle :</p>
 *
 * <pre>
 * {
 *   "content": [ … ],
 *   "page": { "size": 24, "number": 0, "totalElements": 137, "totalPages": 6 }
 * }
 * </pre>
 *
 * <p>📌 <b>Pourquoi maintenant.</b> Ce changement modifie le contrat de l'API.
 * Le faire avant d'écrire la première ligne d'Angular ne coûte rien ; le faire
 * après obligerait à reprendre chaque écran de liste des trois applications.
 * <b>Un avertissement de démarrage est une dette qui n'a pas encore de
 * facture.</b></p>
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class ConfigurationWeb {
}
