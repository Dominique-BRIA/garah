package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.ValeurAttribut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ValeurAttributRepository extends JpaRepository<ValeurAttribut, Long> {

    List<ValeurAttribut> findByAttributIdOrderByOrdreAsc(Long attributId);

    /**
     * Combien de declinaisons portent cette valeur.
     *
     * <p>Sert a REFUSER une suppression avec un message lisible. La cle
     * etrangere de {@code variante_attribut} n'a pas de cascade : la base
     * refuserait de toute facon, mais avec une erreur d'integrite qui ne dit
     * ni combien, ni lesquelles.</p>
     */
    @Query(value = "SELECT count(*) FROM variante_attribut WHERE valeur_attribut_id = :valeurId",
           nativeQuery = true)
    long compterUtilisations(@Param("valeurId") Long valeurId);
}
