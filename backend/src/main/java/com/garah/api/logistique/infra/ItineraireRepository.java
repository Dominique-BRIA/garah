package com.garah.api.logistique.infra;

import com.garah.api.logistique.domaine.Itineraire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ItineraireRepository extends JpaRepository<Itineraire, Long> {

    /**
     * Tous les itinéraires, avec leurs étapes, en <b>une</b> requête.
     *
     * <p>Sans le {@code JOIN FETCH}, afficher dix itinéraires en déclencherait
     * onze : une pour la liste, puis une par itinéraire au moment de lire ses
     * étapes. C'est la règle du projet — une requête par page, jamais une par
     * ligne.</p>
     *
     * <p>{@code LEFT} : un itinéraire sans étape intermédiaire est légitime
     * (Douala → Bangui direct). Une jointure interne le ferait disparaître de
     * la liste.</p>
     */
    @Query("""
            SELECT DISTINCT i FROM Itineraire i
              LEFT JOIN FETCH i.etapes
            """)
    List<Itineraire> listerAvecEtapes();

    @Query("""
            SELECT i FROM Itineraire i
              LEFT JOIN FETCH i.etapes
             WHERE i.id = :id
            """)
    Optional<Itineraire> chargerAvecEtapes(Long id);

    boolean existsByLieuDepartIdOrLieuArriveeId(Long lieuDepartId, Long lieuArriveeId);
}
