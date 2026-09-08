package com.garah.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * Le temps réel des messages.
 *
 * <h2>🎯 Ce que ça remplace</h2>
 *
 * <p>Jusqu'ici, un message reçu n'apparaissait qu'au rechargement. Les
 * conversations client comme les échanges internes passaient par de simples
 * {@code POST}, et l'écran d'en face ne savait rien.</p>
 *
 * <p>⚠️ WebSocket et <b>non</b> un sondage périodique. Un back-office ouvert
 * huit heures par jour qui redemande toutes les cinq secondes, c'est près de
 * six mille requêtes par poste et par jour — pour apprendre presque toujours
 * qu'il ne s'est rien passé. Sur une connexion camerounaise facturée au volume,
 * cela se voit sur la facture avant de se voir à l'écran.</p>
 *
 * <h2>⚠️ L'authentification se fait sur la trame CONNECT, pas sur la poignée
 * de main</h2>
 *
 * <p>L'API WebSocket du navigateur ne permet <b>pas</b> d'ajouter un en-tête
 * {@code Authorization} à l'ouverture. C'est une limite du standard, pas un
 * oubli : les seules manières de porter le jeton dans la poignée de main sont
 * l'URL — où il finirait dans les journaux du serveur et du mandataire — ou un
 * cookie, qui rouvrirait la porte au CSRF que
 * {@link FiltreOrigineCsrf} ferme ailleurs.</p>
 *
 * <p>On l'attend donc dans un en-tête STOMP de la trame {@code CONNECT}, qui
 * est un message applicatif : il ne passe ni par l'URL, ni par un cookie.</p>
 *
 * <h2>⚠️ Un client ne s'abonne qu'à SA file</h2>
 *
 * <p>Le préfixe {@code /utilisateur} est résolu par Spring vers la session de
 * l'appelant. Une destination partagée comme {@code /sujet/messages} aurait
 * livré chaque message à tous les connectés — y compris les échanges internes
 * de l'encadrement.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class ConfigurationWebSocket implements WebSocketMessageBrokerConfigurer {

    /** Le même en-tête que celui posé par les trois applications Angular. */
    public static final String ENTETE_JETON = "Authorization";

    private final JwtDecoder decodeur;

    // ⚠️ La MÊME variable que CORS, et surtout pas une seconde liste : deux
    //    listes d'origines finiraient par diverger, et le WebSocket resterait
    //    ouvert là où HTTP a été fermé.
    @Value("${GARAH_CORS_ORIGINS:http://localhost:4200}")
    private String origines;

    public ConfigurationWebSocket(JwtDecoder decodeur) {
        this.decodeur = decodeur;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registre) {
        // Un courtier EN MÉMOIRE, et c'est un choix assumé.
        //
        // ⚠️ Il ne fonctionne que sur UNE instance : deux serveurs derrière un
        //    répartiteur ne se transmettraient pas les messages, et un agent
        //    connecté au second ne verrait jamais ce qui passe par le premier.
        //    GARAH tourne aujourd'hui sur une seule instance Azure. Le jour où
        //    ce ne sera plus vrai, il faudra un courtier externe — c'est un
        //    changement de configuration, pas de code.
        registre.enableSimpleBroker("/sujet", "/file");

        // Ce que le client ENVOIE passe par là. Aujourd'hui rien n'est envoyé
        // par WebSocket : on écrit toujours en HTTP, qui sait dire « refusé »
        // et pourquoi. Le temps réel ne sert qu'à RECEVOIR.
        registre.setApplicationDestinationPrefixes("/app");

        registre.setUserDestinationPrefix("/utilisateur");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registre) {
        registre.addEndpoint("/ws")
                // Les mêmes origines que le reste : un WebSocket ouvert à tous
                // contournerait la politique d'origine que CORS applique
                // ailleurs.
                .setAllowedOrigins(origines.split("\\s*,\\s*"));
    }

    /**
     * Le contrôle du jeton, à la connexion.
     *
     * <p>⚠️ {@code CONNECT} <b>seulement</b>. Revalider à chaque trame
     * rejetterait l'abonné dès l'expiration du jeton — au bout de quinze
     * minutes — alors qu'il est devant son écran et n'a rien fait de mal. La
     * session vaut pour la durée de la connexion ; c'est la reconnexion qui
     * redemande un jeton valide.</p>
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel canal) {
                StompHeaderAccessor entetes = MessageHeaderAccessor
                        .getAccessor(message, StompHeaderAccessor.class);

                if (entetes == null || !StompCommand.CONNECT.equals(entetes.getCommand())) {
                    return message;
                }

                List<String> valeurs = entetes.getNativeHeader(ENTETE_JETON);
                if (valeurs == null || valeurs.isEmpty()) {
                    // Pas de jeton : la connexion s'ouvre sans identité, et
                    // aucune file personnelle ne lui sera adressée. On ne
                    // refuse pas — un refus brutal ferait boucler le client
                    // sur des reconnexions.
                    return message;
                }

                String brut = valeurs.getFirst();
                String jeton = brut.startsWith("Bearer ") ? brut.substring(7) : brut;

                try {
                    Jwt decode = decodeur.decode(jeton);
                    // Le SUJET du jeton est l'identifiant de l'utilisateur :
                    // c'est lui qui nomme la file personnelle, et c'est le
                    // même nom que côté HTTP.
                    entetes.setUser(new UsernamePasswordAuthenticationToken(
                            decode.getSubject(), null, List.of()));
                } catch (Exception refus) {
                    // Jeton expiré ou forgé : connexion anonyme. Le client se
                    // reconnectera avec un jeton frais après son
                    // rafraîchissement HTTP.
                    entetes.setUser(null);
                }

                return message;
            }
        });
    }
}
