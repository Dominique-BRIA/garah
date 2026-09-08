package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.NomClient;
import com.garah.api.iam.domaine.ResumeClient;
import com.garah.api.iam.domaine.StatutUtilisateur;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * La liste du back-office.
     *
     * <p>Une <b>projection</b>, jamais l entite : la reponse ne peut donc pas
     * emporter le mot de passe hache, meme si quelqu un ajoute demain un champ
     * a {@code Utilisateur}. C est la meme garantie que {@link #nomsPar}, et
     * c est pour ca qu on n ecrit pas {@code Page<Client>} ici.</p>
     *
     * <p>La recherche porte sur le code, le nom ET l email. Un agent au
     * telephone a l un des trois, jamais les trois — obliger a choisir un
     * champ ferait echouer une recherche sur deux.</p>
     */
    @Query("""
            SELECT new com.garah.api.iam.domaine.ResumeClient(
                       c.id, c.codeClient, u.nom, u.email, u.telephone,
                       u.emailVerifie, CAST(c.statut AS string), c.dateInscription)
              FROM Client c JOIN c.utilisateur u
             WHERE (:statut IS NULL OR c.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(c.codeClient) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                    OR LOWER(u.nom)        LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                    OR LOWER(u.email)      LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
             ORDER BY c.dateInscription DESC
            """)
    Page<ResumeClient> administration(@Param("statut") StatutUtilisateur statut,
                                      @Param("recherche") String recherche,
                                      Pageable pagination);

    /**
     * Un client et son utilisateur, en UNE requete.
     *
     * <p>Sans le {@code JOIN FETCH}, lire le nom apres la transaction leverait
     * un {@code LazyInitializationException} — {@code open-in-view} est a
     * {@code false}. A l execution seulement, jamais a la compilation.</p>
     */
    @Query("""
            SELECT c FROM Client c JOIN FETCH c.utilisateur
             WHERE c.id = :id
            """)
    Optional<Client> chargerAvecUtilisateur(@Param("id") Long id);
}
