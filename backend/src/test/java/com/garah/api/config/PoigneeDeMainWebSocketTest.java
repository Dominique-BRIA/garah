package com.garah.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * La poignée de main WebSocket passe la sécurité sans jeton.
 *
 * <h2>🎯 Ce que ce test empêche de revenir</h2>
 *
 * <p>{@code /ws} n'était pas dans la liste des routes ouvertes. Il tombait
 * donc dans {@code anyRequest().authenticated()} et répondait <b>401</b> —
 * avant même que STOMP ne voie la trame {@code CONNECT} qui porte le jeton.</p>
 *
 * <p>⚠️ RIEN NE LE DISAIT. Le navigateur ne signale qu'une connexion fermée,
 * le client retente indéfiniment comme il doit le faire quand le réseau
 * tombe, et l'écran a simplement l'air de ne pas se mettre à jour. On cherche
 * alors le défaut dans le code du temps réel, qui est juste.</p>
 *
 * <h2>⚠️ Pourquoi l'anonymat est le bon choix, et non un trou</h2>
 *
 * <p>L'API WebSocket du navigateur ne permet pas d'ajouter un en-tête à
 * l'ouverture. Les seules façons de porter le jeton dans la poignée de main
 * seraient l'URL — où il finirait dans les journaux du serveur et du
 * mandataire — ou un cookie, qui rouvrirait le CSRF fermé ailleurs.</p>
 *
 * <p>Le jeton est donc lu sur la trame {@code CONNECT}. Sans jeton valide, la
 * session reste anonyme et aucune file personnelle ne lui est jamais
 * adressée : elle n'apprend rien.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Poignée de main WebSocket")
class PoigneeDeMainWebSocketTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("⚠️ /ws ne répond pas 401 sans jeton : le jeton vient après")
    void wsNEstPasGardeParLaSecuriteHttp() throws Exception {
        int statut = mvc.perform(get("/ws"))
                .andReturn()
                .getResponse()
                .getStatus();

        // On n'exige PAS un code précis. Une requête qui n'est pas une vraie
        // bascule WebSocket est refusée par le point d'entrée lui-même — 400,
        // 404 ou 500 selon la version de Spring — et c'est très bien : ce qui
        // compte est qu'elle soit passée par la SÉCURITÉ.
        //
        // Fixer « 400 » ici ferait échouer le test à la prochaine montée de
        // version pour une raison qui n'a rien à voir avec ce qu'il garde.
        assertThat(statut)
                .as("""
                        /ws répond 401 : la sécurité HTTP le refuse avant que \
                        STOMP ne voie la trame CONNECT.

                        Ajouter dans ConfigurationSecurite :
                            .requestMatchers("/ws").permitAll()

                        La poignée de main est anonyme par construction — le \
                        jeton est contrôlé sur le CONNECT, et sans lui aucune \
                        file personnelle n'est adressée.""")
                .isNotEqualTo(401);
    }
}
