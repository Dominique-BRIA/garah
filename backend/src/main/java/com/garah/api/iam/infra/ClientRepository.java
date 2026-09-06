package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByCodeClient(String codeClient);

    /**
     * Le prochain code client, tire d'une sequence PostgreSQL (V20).
     *
     * <p>Meme raison que {@code commande_numero_seq} : {@code count(*) + 1}
     * donnerait le meme code a deux inscriptions simultanees, et la contrainte
     * UNIQUE ferait echouer une inscription parfaitement valide.</p>
     */
    @Query(value = "SELECT nextval('client_code_seq')", nativeQuery = true)
    long prochainCode();
}
