package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.Produit;
import com.garah.api.catalogue.domaine.StatutProduit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProduitRepository extends JpaRepository<Produit, Long> {

    Optional<Produit> findBySlug(String slug);

    boolean existsByReference(String reference);

    boolean existsBySlug(String slug);

    /**
     * Le catalogue public : uniquement les produits publiés.
     *
     * <p>La pagination n'est pas une option. Sans elle, un {@code findAll()}
     * charge tout le catalogue en mémoire — ça marche avec 50 produits, ça
     * fait tomber le serveur avec 50 000. Et personne ne s'en aperçoit avant
     * que le catalogue ne grossisse.</p>
     */
    Page<Produit> findByStatut(StatutProduit statut, Pageable pagination);

    Page<Produit> findByCategorieIdAndStatut(Long categorieId, StatutProduit statut, Pageable pagination);

    /**
     * La liste du back-office : <b>tous les statuts</b>, brouillons compris.
     *
     * <p>C'est ce qui la distingue du catalogue public. Une liste de gestion
     * qui ne montrerait que les produits publiés cacherait précisément ceux sur
     * lesquels il reste du travail — un brouillon créé le matin serait
     * introuvable l'après-midi.</p>
     *
     * <p>Le {@code JOIN FETCH} sur la catégorie évite une requête par ligne :
     * la relation est {@code LAZY}, et chaque nom de catégorie affiché
     * déclencherait sinon son propre aller-retour.</p>
     */
    /**
     * @param disponibilite {@code TOUS}, {@code EN_STOCK}, {@code FAIBLE} ou
     *                      {@code RUPTURE}
     *
     * <h2>⚠️ Le filtre de stock s'applique EN SQL, jamais après coup</h2>
     *
     * <p>Trier la page une fois reçue donnerait « 3 produits sur 24 » sur une
     * page, « 7 sur 24 » sur la suivante, et un total qui ne correspondrait à
     * rien. Un filtre qui ne participe pas à la pagination n'est pas un
     * filtre.</p>
     *
     * <h2>Pourquoi {@code Stock} apparaît dans une requête du catalogue</h2>
     *
     * <p>Le stock dépend déjà du catalogue pour désigner ce qu'il compte :
     * lui emprunter un dépôt formerait un <b>cycle</b> entre domaines, et
     * {@code ArchitectureTest} refuserait le build. La requête, elle, ne crée
     * aucune dépendance de paquetage — c'est le même arbitrage que
     * {@code VarianteRepository.aDejaServi}, et il tient à un seul endroit.</p>
     *
     * <p>🎯 <b>La somme est portée par le PRODUIT, pas par la variante.</b>
     * Un produit dont une déclinaison est épuisée et une autre disponible
     * reste vendable : le filtrer comme « en rupture » cacherait de la
     * marchandise qui part le jour même.</p>
     *
     * <p>{@code LEFT JOIN} implicite : une variante sans ligne de stock compte
     * pour zéro. Sans le {@code COALESCE}, la somme vaudrait {@code null} et
     * la comparaison serait fausse — le produit disparaîtrait de TOUS les
     * filtres, y compris « en rupture », alors qu'il en est le cas le plus
     * pur.</p>
     */
    @Query(value = """
            SELECT p FROM Produit p
              JOIN FETCH p.categorie
             WHERE (:statut IS NULL OR p.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(p.nom) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                    OR LOWER(p.reference) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
               AND (:disponibilite = 'TOUS'
                    OR (:disponibilite = 'RUPTURE'  AND (SELECT COALESCE(SUM(s.quantiteDisponible), 0)
                                                           FROM Stock s
                                                          WHERE s.varianteId IN (SELECT v.id FROM Variante v
                                                                                  WHERE v.produit.id = p.id)) = 0)
                    OR (:disponibilite = 'EN_STOCK' AND (SELECT COALESCE(SUM(s.quantiteDisponible), 0)
                                                           FROM Stock s
                                                          WHERE s.varianteId IN (SELECT v.id FROM Variante v
                                                                                  WHERE v.produit.id = p.id)) > 0)
                    OR (:disponibilite = 'FAIBLE'   AND EXISTS (SELECT 1 FROM Stock s
                                                                 WHERE s.varianteId IN (SELECT v.id FROM Variante v
                                                                                         WHERE v.produit.id = p.id)
                                                                   AND s.quantiteDisponible > 0
                                                                   AND s.quantiteDisponible <= s.seuilAlerte)))
            """,
            countQuery = """
            SELECT count(p) FROM Produit p
             WHERE (:statut IS NULL OR p.statut = :statut)
               AND (:recherche IS NULL
                    OR LOWER(p.nom) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%'))
                    OR LOWER(p.reference) LIKE LOWER(CONCAT('%', CAST(:recherche AS string), '%')))
               AND (:disponibilite = 'TOUS'
                    OR (:disponibilite = 'RUPTURE'  AND (SELECT COALESCE(SUM(s.quantiteDisponible), 0)
                                                           FROM Stock s
                                                          WHERE s.varianteId IN (SELECT v.id FROM Variante v
                                                                                  WHERE v.produit.id = p.id)) = 0)
                    OR (:disponibilite = 'EN_STOCK' AND (SELECT COALESCE(SUM(s.quantiteDisponible), 0)
                                                           FROM Stock s
                                                          WHERE s.varianteId IN (SELECT v.id FROM Variante v
                                                                                  WHERE v.produit.id = p.id)) > 0)
                    OR (:disponibilite = 'FAIBLE'   AND EXISTS (SELECT 1 FROM Stock s
                                                                 WHERE s.varianteId IN (SELECT v.id FROM Variante v
                                                                                         WHERE v.produit.id = p.id)
                                                                   AND s.quantiteDisponible > 0
                                                                   AND s.quantiteDisponible <= s.seuilAlerte)))
            """)
    Page<Produit> administration(@Param("recherche") String recherche,
                                 @Param("statut") StatutProduit statut,
                                 @Param("disponibilite") String disponibilite,
                                 Pageable pagination);

    /**
     * Charge un produit avec ses variantes.
     *
     * <p>⚠️ <b>Pourquoi DEUX methodes plutot qu'une seule.</b> La premiere
     * version faisait les deux {@code JOIN FETCH} dans la meme requete, en
     * pariant sur un simple produit cartesien dedoublonne par
     * {@code DISTINCT}. Hibernate refuse purement et simplement :</p>
     *
     * <pre>MultipleBagFetchException: cannot simultaneously fetch multiple bags:
     *     [Produit.medias, Produit.variantes]</pre>
     *
     * <p>Un « bag » est une {@code List} sans colonne d'ordre : Hibernate ne
     * peut pas savoir quelle ligne du produit cartesien appartient a quelle
     * collection, donc il refuse d'essayer plutot que de renvoyer des doublons
     * silencieux. <b>Le refus est le bon comportement.</b></p>
     *
     * <p>La correction consiste a charger <b>une collection a la fois</b>. Les
     * deux requetes renvoient la MEME instance geree — c'est le contexte de
     * persistance qui les reunit, sans produit cartesien.</p>
     *
     * @see #chargerAvecMedias(Long)
     */
    @Query("""
            SELECT p FROM Produit p
              LEFT JOIN FETCH p.variantes
             WHERE p.id = :id
            """)
    Optional<Produit> chargerAvecVariantes(Long id);

    /**
     * Initialise les medias du produit deja charge dans la transaction.
     *
     * <p>A appeler dans la MEME transaction que
     * {@link #chargerAvecVariantes(Long)} : Hibernate reconnait l'entite deja
     * presente dans le contexte de persistance et se contente de remplir sa
     * collection {@code medias}.</p>
     */
    @Query("""
            SELECT p FROM Produit p
              LEFT JOIN FETCH p.medias
             WHERE p.id = :id
            """)
    Optional<Produit> chargerAvecMedias(Long id);

    // =========================================================================
    // La corbeille (V27)
    // =========================================================================
    // 🎯 TOUT CE BLOC EST EN SQL NATIF, ET IL LE DOIT.
    //
    // `Produit` porte un @SQLRestriction("date_suppression IS NULL") : en JPA,
    // un produit en corbeille N EXISTE PAS. `findById` ne le trouve pas, aucune
    // liste ne le montre — c est precisement ce qu on veut partout ailleurs.
    //
    // Le SQL natif echappe a cette restriction. C est donc le seul endroit du
    // code qui peut voir ces lignes, et c est voulu : la porte est unique et
    // nommee, au lieu d etre un drapeau que chaque requete pourrait oublier.
    //
    // ⚠️ Consequence : ne JAMAIS ajouter ici une requete native qui liste des
    //    produits sans poser explicitement sa condition sur date_suppression.
    //    Elle verrait la corbeille, et personne ne comprendrait pourquoi.
    // =========================================================================

    /**
     * Les produits en corbeille, du plus recemment jete au plus ancien.
     *
     * <p>L ordre n est pas cosmetique : on vient presque toujours chercher ce
     * qu on vient de supprimer par erreur.</p>
     */
    @Query(value = """
            SELECT p.id, p.reference, p.nom, p.statut, c.nom AS categorie_nom,
                   p.date_suppression
              FROM produit p
              JOIN categorie_produit c ON c.id = p.categorie_id
             WHERE p.date_suppression IS NOT NULL
             ORDER BY p.date_suppression DESC
            """,
            countQuery = """
            SELECT count(*) FROM produit WHERE date_suppression IS NOT NULL
            """,
            nativeQuery = true)
    Page<LigneCorbeille> corbeille(Pageable pagination);

    /**
     * La projection de {@link #corbeille}.
     *
     * <p>⚠️ Une interface, et non un {@code Object[]} qu'on transtype. La
     * premiere version lisait {@code (java.sql.Timestamp) ligne[5]} et
     * echouait a l execution : le pilote PostgreSQL rend un {@link Instant}
     * pour un {@code timestamptz}. Un transtypage sur un tableau ne se verifie
     * a aucun moment de la compilation — ici, Spring Data fait la conversion
     * et le type est tenu.</p>
     */
    interface LigneCorbeille {
        Long getId();
        String getReference();
        String getNom();
        String getStatut();
        String getCategorieNom();
        Instant getDateSuppression();
    }

    /** Le nom d un produit en corbeille, pour les messages. Vide s il n y est pas. */
    @Query(value = """
            SELECT nom FROM produit
             WHERE id = :id AND date_suppression IS NOT NULL
            """, nativeQuery = true)
    Optional<String> nomDansCorbeille(@Param("id") Long id);

    /**
     * Sort un produit de la corbeille.
     *
     * <p>En UPDATE natif plutot qu en chargeant l entite : JPA ne peut pas la
     * charger, la restriction la masque.</p>
     *
     * @return 1 si un produit a ete restaure, 0 s il n etait pas en corbeille
     */
    @Modifying
    @Query(value = """
            UPDATE produit SET date_suppression = NULL
             WHERE id = :id AND date_suppression IS NOT NULL
            """, nativeQuery = true)
    int restaurer(@Param("id") Long id);

    /**
     * Efface pour de bon.
     *
     * <p>⚠️ La condition {@code date_suppression IS NOT NULL} n est pas une
     * precaution de style : elle rend impossible d effacer un produit ACTIF
     * par cette porte. Sans elle, un identifiant errone supprimerait
     * definitivement un produit en vente, sans passer par la corbeille.</p>
     *
     * <p>Les variantes, medias, tarifications et lignes de stock partent en
     * cascade (V4, V5). Les fichiers du stockage, eux, sont a retirer par
     * l appelant : la base ne les connait pas.</p>
     */
    @Modifying
    @Query(value = """
            DELETE FROM produit
             WHERE id = :id AND date_suppression IS NOT NULL
            """, nativeQuery = true)
    int supprimerDefinitivement(@Param("id") Long id);

    /** Les cles d objet des medias d un produit en corbeille, avant de l effacer. */
    @Query(value = """
            SELECT m.cle_objet FROM media m
             WHERE m.produit_id = :id AND m.cle_objet IS NOT NULL
            """, nativeQuery = true)
    List<String> clesMediasDe(@Param("id") Long id);
}
