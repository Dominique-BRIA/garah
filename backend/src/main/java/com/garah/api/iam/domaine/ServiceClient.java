package com.garah.api.iam.domaine;

import com.garah.api.iam.infra.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * La lecture des clients, pour les domaines qui les affichent sans les gérer.
 *
 * <p>Une commande ne porte qu'un {@code clientId} : les deux domaines restent
 * séparés, et c'est voulu. Mais une liste de commandes qui n'afficherait qu'un
 * identifiant numérique serait illisible — il faut bien traverser la
 * frontière quelque part. Ici, en lecture seule, par une projection.</p>
 */
@Service
public class ServiceClient {

    private final ClientRepository clients;

    public ServiceClient(ClientRepository clients) {
        this.clients = clients;
    }

    /**
     * De quoi désigner plusieurs clients, indexés par identifiant.
     *
     * <p>Une requête pour toute la page, pas une par ligne.</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, NomClient> nomsPar(Collection<Long> ids) {
        if (ids.isEmpty()) {
            // `IN ()` est une requête invalide en SQL : on ne la lance pas.
            return Map.of();
        }
        return clients.nomsPar(ids).stream()
                .collect(Collectors.toMap(NomClient::id, Function.identity()));
    }
}
