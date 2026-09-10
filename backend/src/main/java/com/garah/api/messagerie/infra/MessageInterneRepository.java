package com.garah.api.messagerie.infra;

import com.garah.api.messagerie.domaine.MessageInterne;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MessageInterneRepository extends JpaRepository<MessageInterne, Long> {

    List<MessageInterne> findByFilIdOrderByDateEnvoiAsc(Long filId);

    /**
     * Combien de messages non lus, fil par fil.
     *
     * <p>⚠️ UNE requete pour toute la liste, jamais une par fil. Une pastille
     *    par ligne est exactement le genre de detail qui multiplie
     *    silencieusement les requetes.</p>
     *
     * <p>On ne compte que ce que l AUTRE a ecrit : ses propres messages non
     *    lus n existent pas.</p>
     */
    @Query("""
            SELECT m.filId, count(m)
              FROM MessageInterne m
             WHERE m.filId IN :fils
               AND m.expediteurId <> :moi
               AND m.dateLecture IS NULL
             GROUP BY m.filId
            """)
    List<Object[]> nonLusPar(@Param("fils") Collection<Long> fils, @Param("moi") Long responsableId);

    /**
     * Le total non lu, pour la pastille de l en-tete.
     *
     * <p>Elle s affiche sur CHAQUE ecran du back-office : la calculer en
     * parcourant les fils couterait une requete par page affichee.</p>
     */
    @Query("""
            SELECT count(m) FROM MessageInterne m
              JOIN FilInterne f ON f.id = m.filId
             WHERE (f.utilisateurA = :moi OR f.utilisateurB = :moi)
               AND m.expediteurId <> :moi
               AND m.dateLecture IS NULL
            """)
    long totalNonLus(@Param("moi") Long responsableId);
}
