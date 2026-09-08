package com.garah.api.stock.infra;

import com.garah.api.stock.domaine.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findByVarianteId(Long varianteId);

    /**
     * Charge le stock en le <b>verrouillant</b> jusqu'à la fin de la transaction.
     *
     * <p>Traduit en SQL par un {@code SELECT … FOR UPDATE}. Deux transactions
     * qui demandent la même variante sont <b>sérialisées</b> : la seconde
     * attend que la première ait validé ou annulé.</p>
     *
     * <p>C'est ce qui rend impossible le scénario du chapitre 05 :</p>
     * <pre>
     * Client A          Client B
     *  lit 1             lit 1          ← les deux voient « il en reste 1 »
     *  écrit 0           écrit 0        ← l'article est vendu DEUX fois
     * </pre>
     *
     * <p>⚠️ Le verrou porte sur <b>une ligne</b>, donc sur une variante.
     * Deux commandes de produits différents ne s'attendent pas. C'est ce qui
     * rend le coût acceptable : on ne sérialise que ce qui est réellement en
     * concurrence.</p>
     *
     * <p>⚠️ Et il impose une discipline : <b>toujours verrouiller les variantes
     * dans le même ordre</b> quand une commande en contient plusieurs, sinon
     * deux transactions peuvent s'attendre mutuellement (interblocage).
     * C'est le rôle de {@link #verrouillerPlusieurs}.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.varianteId = :varianteId")
    Optional<Stock> verrouiller(Long varianteId);

    /**
     * Verrouille plusieurs stocks, <b>toujours dans le même ordre</b>.
     *
     * <p>Le {@code ORDER BY} n'est pas cosmétique : il évite l'interblocage.</p>
     *
     * <pre>
     * Sans ordre imposé :
     *   Transaction A verrouille la variante 7, puis demande la 3
     *   Transaction B verrouille la variante 3, puis demande la 7
     *   → chacune attend l'autre, indéfiniment
     * </pre>
     *
     * <p>En verrouillant toujours par identifiant croissant, ce cycle ne peut
     * pas se former.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.varianteId IN :varianteIds ORDER BY s.varianteId")
    List<Stock> verrouillerPlusieurs(List<Long> varianteIds);

    @Query("SELECT s FROM Stock s WHERE s.quantiteDisponible <= s.seuilAlerte")
    List<Stock> sousLeSeuil();

    /** Le NOMBRE d alertes, sans charger les lignes : pour le tableau de bord. */
    @Query("SELECT count(s) FROM Stock s WHERE s.quantiteDisponible <= s.seuilAlerte")
    long compterSousLeSeuil();

    /**
     * Les stocks de plusieurs declinaisons, en <b>une</b> requete.
     *
     * <p>Sert a la fiche produit : elle affiche toutes ses declinaisons d un
     * coup, et interroger le stock ligne par ligne ferait une requete par
     * declinaison affichee.</p>
     */
    List<Stock> findByVarianteIdIn(Collection<Long> varianteIds);

    /**
     * La liste du back-office.
     *
     * <p>{@code varianteIds} nul = aucun filtre. Quand une recherche est en
     * cours, le service demande d'abord au catalogue quelles declinaisons
     * correspondent, puis passe leurs identifiants ici : le stock ne connait
     * que des identifiants, il ne sait pas ce qu'est une « chaussure ».</p>
     *
     * <p>Le tri place les ruptures EN PREMIER. Un ecran de stock s'ouvre pour
     * savoir ce qui manque, pas pour admirer ce qui est plein.</p>
     */
    @Query("""
            SELECT s FROM Stock s
             WHERE (:varianteIds IS NULL OR s.varianteId IN :varianteIds)
               AND (:sousLeSeuil = false OR s.quantiteDisponible <= s.seuilAlerte)
             ORDER BY s.quantiteDisponible ASC, s.varianteId ASC
            """)
    Page<Stock> administration(@Param("varianteIds") Collection<Long> varianteIds,
                               @Param("sousLeSeuil") boolean sousLeSeuil,
                               Pageable pagination);
}
