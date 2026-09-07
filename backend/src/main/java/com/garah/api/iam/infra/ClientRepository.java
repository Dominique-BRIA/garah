package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.NomClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /**
     * De quoi designer plusieurs clients, en <b>une</b> requete.
     *
     * <p>Sert aux listes d'un autre domaine — « de qui vient cette
     * commande ». Charger le client ligne par ligne ferait une requete par
     * commande affichee.</p>
     *
     * <p>C'est une <b>projection</b> : quatre colonnes, aucune entite. Le
     * domaine appelant ne peut donc pas modifier un client par inadvertance,
     * ni recevoir son mot de passe hache dans une reponse JSON.</p>
     */
    @Query("""
            SELECT new com.garah.api.iam.domaine.NomClient(
                       c.id, c.codeClient, u.nom, u.email)
              FROM Client c JOIN c.utilisateur u
             WHERE c.id IN :ids
            """)
    List<NomClient> nomsPar(@Param("ids") Collection<Long> ids);
}
