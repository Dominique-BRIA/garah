package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.CodeConnexion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface CodeConnexionRepository extends JpaRepository<CodeConnexion, Long> {

    /**
     * Le code vivant d'un numéro — il ne peut y en avoir qu'un.
     *
     * <p>L'index unique partiel {@code code_connexion_un_seul_vivant} le
     * garantit en base ; cette méthode s'appuie dessus plutôt que de trier par
     * date en espérant tomber sur le bon.</p>
     */
    Optional<CodeConnexion> findByTelephoneAndConsommeFalse(String telephone);

    /**
     * Invalide le code précédent avant d'en émettre un nouveau.
     *
     * <p>⚠️ Indispensable, et pas seulement pour satisfaire l'index unique :
     * laisser vivre plusieurs codes multiplierait les chances d'en deviner un,
     * et permettrait d'inonder le téléphone de quelqu'un d'autre.</p>
     */
    @Modifying
    @Query("UPDATE CodeConnexion c SET c.consomme = true "
            + "WHERE c.telephone = :telephone AND c.consomme = false")
    int invalider(String telephone);

    /**
     * Efface les codes expirés.
     *
     * <p>Une empreinte de secret périmé n'apporte plus rien et reste une
     * donnée à protéger. On ne garde pas ce qui ne sert plus.</p>
     */
    @Modifying
    @Query("DELETE FROM CodeConnexion c WHERE c.dateExpiration < :avant")
    int purger(Instant avant);
}
