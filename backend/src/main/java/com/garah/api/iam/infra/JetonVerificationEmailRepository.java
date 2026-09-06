package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.JetonVerificationEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface JetonVerificationEmailRepository
        extends JpaRepository<JetonVerificationEmail, Long> {

    Optional<JetonVerificationEmail> findByEmpreinte(String empreinte);

    /**
     * Invalide les jetons encore ouverts d un utilisateur.
     *
     * <p>Appele avant d en emettre un nouveau. Sans cela, chaque demande de
     * renvoi laisserait le precedent valide : trois renvois donneraient trois
     * liens actifs, dont deux dans des boites mail qu on ne controle plus —
     * un ancien e-mail transfere, une capture d ecran partagee.</p>
     *
     * <p>On marque « utilise » plutot que de supprimer : la trace de ce qui a
     * ete emis reste, et c est elle qui permet de comprendre un incident.</p>
     */
    @Modifying
    @Query("""
            UPDATE JetonVerificationEmail j
               SET j.dateUtilisation = :maintenant
             WHERE j.utilisateurId = :utilisateurId
               AND j.dateUtilisation IS NULL
            """)
    int invaliderLesOuverts(@Param("utilisateurId") Long utilisateurId,
                            @Param("maintenant") Instant maintenant);

    /**
     * Combien de jetons ont ete emis pour cet utilisateur depuis une date.
     *
     * <p>Deuxieme ligne de defense derriere la limitation de debit par IP :
     * celle-ci compte par COMPTE. Un attaquant qui change d adresse IP ne peut
     * toujours pas faire pleuvoir des e-mails sur la meme victime.</p>
     */
    @Query("""
            SELECT count(j) FROM JetonVerificationEmail j
             WHERE j.utilisateurId = :utilisateurId
               AND j.dateCreation > :depuis
            """)
    long comptesDepuis(@Param("utilisateurId") Long utilisateurId,
                       @Param("depuis") Instant depuis);

    /**
     * Supprime les jetons expires depuis un moment.
     *
     * <p>Une ligne par inscription et par renvoi : sans purge, la table ne
     * redescend jamais. C est la lecon de {@code vue_produit} (D-15).</p>
     */
    @Modifying
    @Query("DELETE FROM JetonVerificationEmail j WHERE j.dateExpiration < :limite")
    int purger(@Param("limite") Instant limite);
}
