package com.garah.api.messagerie.infra;

import com.garah.api.messagerie.domaine.FilInterne;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FilInterneRepository extends JpaRepository<FilInterne, Long> {

    /**
     * Le fil entre deux personnes, dans n importe quel ordre de saisie.
     *
     * <p>La paire est rangee avant l appel ; cette requete la cherche telle
     * qu elle est stockee.</p>
     */
    Optional<FilInterne> findByResponsableAAndResponsableB(Long a, Long b);

    /**
     * Mes fils, le plus recent d abord.
     *
     * <p>⚠️ Deux colonnes a tester, parce qu on peut etre d un cote comme de l
     *    autre. C est le prix de la paire ordonnee — et il est mille fois
     *    moins cher que deux fils pour une meme discussion.</p>
     */
    @Query("""
            SELECT f FROM FilInterne f
             WHERE f.responsableA = :moi OR f.responsableB = :moi
             ORDER BY f.dateDernier DESC
            """)
    List<FilInterne> miens(@Param("moi") Long responsableId);
}
