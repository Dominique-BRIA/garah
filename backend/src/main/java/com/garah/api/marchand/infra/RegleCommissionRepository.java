package com.garah.api.marchand.infra;

import com.garah.api.marchand.domaine.RegleCommission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface RegleCommissionRepository extends JpaRepository<RegleCommission, Long> {

    /**
     * Les règles applicables à un couple (marchand, catégorie), à une date,
     * de la plus spécifique à la plus générale.
     *
     * <p>Le tri encode la spécificité :</p>
     * <ol>
     *   <li>{@code priorite} décroissante — un réglage explicite l'emporte ;</li>
     *   <li>une règle qui nomme le <b>marchand</b> bat une règle générale ;</li>
     *   <li>puis une règle qui nomme la <b>catégorie</b>.</li>
     * </ol>
     *
     * <p>Le service prend la première. Renvoyer une liste plutôt qu'un
     * {@code Optional} est délibéré : ici, contrairement aux paliers de prix,
     * <b>plusieurs règles peuvent légitimement s'appliquer</b> — il faut
     * choisir, et le tri rend ce choix explicite et reproductible.</p>
     */
    @Query("""
            SELECT r FROM RegleCommission r
             WHERE (r.marchandId IS NULL OR r.marchandId = :marchandId)
               AND (r.categorieProduitId IS NULL OR r.categorieProduitId = :categorieId)
               AND r.dateDebut <= :jour
               AND (r.dateFin IS NULL OR r.dateFin > :jour)
             ORDER BY r.priorite DESC,
                      CASE WHEN r.marchandId IS NOT NULL THEN 0 ELSE 1 END,
                      CASE WHEN r.categorieProduitId IS NOT NULL THEN 0 ELSE 1 END
            """)
    List<RegleCommission> applicables(@Param("marchandId") Long marchandId,
                                      @Param("categorieId") Long categorieId,
                                      @Param("jour") LocalDate jour);

    /**
     * Le nom des categories citees par des regles, en UNE requete.
     *
     * <h2>Pourquoi {@code CategorieProduit} apparait dans une requete de
     * marchand</h2>
     *
     * <p>Le domaine <b>catalogue depend deja de marchand</b> : un produit
     * appartient a un marchand. Appeler catalogue depuis ici formerait un
     * CYCLE, et {@code ArchitectureTest} refuserait le build.</p>
     *
     * <p>La requete, elle, ne cree aucune dependance de paquetage : le nom
     * {@code CategorieProduit} n apparait que dans du HQL, jamais dans un
     * {@code import}. C est le meme arbitrage que {@code ExpeditionRepository}
     * pour les commandes, et il tient a un seul endroit — ici.</p>
     */
    @Query("SELECT c.id, c.nom FROM CategorieProduit c WHERE c.id IN :ids")
    List<Object[]> nomsCategories(@Param("ids") Collection<Long> ids);

    /** Meme arbitrage : verifier qu une categorie existe sans importer catalogue. */
    @Query("SELECT count(c) FROM CategorieProduit c WHERE c.id = :id")
    long compterCategorie(@Param("id") Long id);
}
