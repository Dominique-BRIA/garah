package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.PrixMinProduit;
import com.garah.api.catalogue.domaine.Tarification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TarificationRepository extends JpaRepository<Tarification, Long> {

    List<Tarification> findByVarianteIdOrderByQuantiteMinAsc(Long varianteId);

    /** Le produit a-t-il au moins un prix sur une variante active ? (invariant I-12) */
    @Query("""
            SELECT count(t) > 0 FROM Tarification t
             WHERE t.variante.produit.id = :produitId
               AND t.variante.statut = 'ACTIVE'
            """)
    boolean produitAUnPrix(Long produitId);

    /**
     * Le palier applicable à une quantité, à une date.
     *
     * <p>Cette requête renvoie <b>au plus une</b> ligne, et ce n'est pas une
     * espérance : la contrainte d'exclusion {@code tarification_sans_chevauchement}
     * rend deux paliers concurrents impossibles à écrire. Sans elle, il
     * faudrait un {@code ORDER BY} arbitraire — donc un prix qui dépendrait de
     * l'ordre d'insertion.</p>
     *
     * <p>La borne haute de la période est <b>exclusive</b> ({@code dateFin > jour}),
     * exactement comme le {@code daterange} de PostgreSQL. Les deux doivent
     * raisonner pareil, sinon un palier serait valide côté Java et clos côté
     * base.</p>
     */
    @Query("""
            SELECT t FROM Tarification t
             WHERE t.variante.id = :varianteId
               AND t.quantiteMin <= :quantite
               AND (t.quantiteMax IS NULL OR t.quantiteMax >= :quantite)
               AND t.dateDebut <= :jour
               AND (t.dateFin IS NULL OR t.dateFin > :jour)
            """)
    Optional<Tarification> palierApplicable(@Param("varianteId") Long varianteId,
                                            @Param("quantite") int quantite,
                                            @Param("jour") LocalDate jour);

    /** La grille en vigueur à une date, du plus petit palier au plus grand. */
    @Query("""
            SELECT t FROM Tarification t
             WHERE t.variante.id = :varianteId
               AND t.dateDebut <= :jour
               AND (t.dateFin IS NULL OR t.dateFin > :jour)
             ORDER BY t.quantiteMin
            """)
    List<Tarification> paliersEnVigueur(@Param("varianteId") Long varianteId,
                                        @Param("jour") LocalDate jour);

    /**
     * Un palier en vigueur recouvre-t-il déjà cette plage de quantités ?
     *
     * <p>Deux intervalles se chevauchent si chacun commence avant la fin de
     * l'autre. C'est exactement ce que teste l'opérateur {@code &&} de
     * PostgreSQL ; on le réécrit ici en JPQL pour produire un message lisible
     * <b>avant</b> que la base ne refuse.</p>
     */
    @Query("""
            SELECT count(t) > 0 FROM Tarification t
             WHERE t.variante.id = :varianteId
               AND t.quantiteMin <= :max
               AND COALESCE(t.quantiteMax, 2147483647) >= :min
               AND t.dateDebut <= :jour
               AND (t.dateFin IS NULL OR t.dateFin > :jour)
            """)
    boolean existeChevauchement(@Param("varianteId") Long varianteId,
                                @Param("min") int min,
                                @Param("max") int max,
                                @Param("jour") LocalDate jour);

    /**
     * Le prix d'appel de plusieurs produits, en <b>une</b> requête.
     *
     * <p>⚠️ Le point de cette méthode est le {@code IN :produitIds}. La version
     * naturelle — appeler {@code grille()} pour chaque ligne de la page — fait
     * vingt-quatre allers-retours pour afficher vingt-quatre produits. On ne le
     * voit pas en développement, avec une base locale et trois produits ; on le
     * voit sur une connexion mobile et une base distante.</p>
     *
     * <p>Seules les déclinaisons <b>actives</b> comptent : afficher « à partir
     * de 3 000 » d'après une déclinaison retirée de la vente promettrait un
     * prix qu'aucun client ne peut obtenir.</p>
     *
     * <p>Le regroupement porte aussi sur la devise. Un produit n'en a qu'une en
     * pratique, mais grouper sur elle évite d'avoir à choisir arbitrairement
     * laquelle garder si ce n'était plus vrai.</p>
     */
    @Query("""
            SELECT new com.garah.api.catalogue.domaine.PrixMinProduit(
                       t.variante.produit.id, MIN(t.prixUnitaire), t.devise)
              FROM Tarification t
             WHERE t.variante.produit.id IN :produitIds
               AND t.variante.statut = 'ACTIVE'
               AND t.dateDebut <= :jour
               AND (t.dateFin IS NULL OR t.dateFin > :jour)
             GROUP BY t.variante.produit.id, t.devise
            """)
    List<PrixMinProduit> prixMinPar(@Param("produitIds") Collection<Long> produitIds,
                                    @Param("jour") LocalDate jour);
}
