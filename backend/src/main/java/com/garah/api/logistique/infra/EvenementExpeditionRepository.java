package com.garah.api.logistique.infra;

import com.garah.api.logistique.domaine.EvenementExpedition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvenementExpeditionRepository extends JpaRepository<EvenementExpedition, Long> {

    /** Le parcours complet d un colis, du plus ancien au plus recent. */
    List<EvenementExpedition> findByColisIdOrderByDateHeureAsc(Long colisId);

    /**
     * Le dernier evenement — celui dont le statut est la projection.
     *
     * <p>Le tri porte sur la date PUIS sur l identifiant : deux evenements
     * enregistres dans la meme milliseconde auraient sinon un ordre
     * indetermine, et la projection deviendrait non deterministe.</p>
     */
    Optional<EvenementExpedition> findFirstByColisIdOrderByDateHeureDescIdDesc(Long colisId);
}
