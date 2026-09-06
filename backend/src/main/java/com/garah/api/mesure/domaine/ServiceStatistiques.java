package com.garah.api.mesure.domaine;

import com.garah.api.mesure.infra.FavoriRepository;
import com.garah.api.mesure.infra.VueProduitRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * La mesure et les statistiques.
 *
 * <p>Deux régimes très différents cohabitent ici :</p>
 *
 * <pre>
 * ÉCRITURE   détaillée, en continu, une ligne par événement
 * LECTURE    agrégée, sur des lignes pré-calculées
 * </pre>
 *
 * <p>Les confondre est l'erreur classique : afficher un tableau de bord en
 * scannant la table de détail fonctionne avec 50 produits et fait tomber le
 * serveur avec 50 000.</p>
 */
@Service
public class ServiceStatistiques {

    /** Rétention du détail (D-15). L'agrégat, lui, est conservé indéfiniment. */
    private static final Duration RETENTION_DETAIL = Duration.ofDays(90);

    private final VueProduitRepository vues;
    private final FavoriRepository favoris;
    private final JdbcTemplate jdbc;

    /**
     * Une transaction indépendante, dont le commit a lieu là où on l'appelle.
     *
     * <p>C'est ce qui permet d'attraper une erreur de commit — voir
     * {@link #enregistrerVue}.</p>
     */
    private final org.springframework.transaction.support.TransactionTemplate transactionIsolee;

    public ServiceStatistiques(VueProduitRepository vues, FavoriRepository favoris,
                               JdbcTemplate jdbc,
                               org.springframework.transaction.PlatformTransactionManager transactions) {
        this.vues = vues;
        this.favoris = favoris;
        this.jdbc = jdbc;

        this.transactionIsolee =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.transactionIsolee.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // -------------------------------------------------------------------------
    // Collecte
    // -------------------------------------------------------------------------

    /**
     * Enregistre une consultation de fiche.
     *
     * <p>⚠️ Appelé sur <b>chaque</b> affichage de produit. C'est donc la
     * méthode la plus appelée de toute l'application — et elle doit être la
     * plus légère : une insertion, aucune lecture, aucun calcul.</p>
     *
     * <p>Elle ne doit jamais faire échouer l'affichage : une statistique
     * perdue est regrettable, une fiche produit en erreur est un client perdu.</p>
     *
     * <p>⚠️ <b>{@code REQUIRES_NEW} est indispensable, et ce n'est pas
     * évident.</b> Ma première version se contentait d'un {@code try/catch}
     * dans la transaction courante — et le test a échoué :</p>
     *
     * <pre>UnexpectedRollbackException: Transaction silently rolled back
     *                                because it has been marked as rollback-only</pre>
     *
     * <p>Attraper une exception <b>n'annule pas</b> le marquage « rollback
     * only » posé par la base. La transaction est déjà condamnée : tout ce
     * qu'on écrira ensuite sera perdu, et l'appelant recevra une erreur
     * incompréhensible au moment du commit.</p>
     *
     * <p><b>Et {@code @Transactional(REQUIRES_NEW)} ne suffisait pas non
     * plus.</b> Deuxième échec du même test : avec l'annotation, le commit a
     * lieu <b>après</b> la sortie de la méthode, donc <b>après</b> le
     * {@code catch}. L'exception surgit au moment du commit et traverse le
     * bloc protégé sans être vue.</p>
     *
     * <p>Il faut donc que le commit se produise <b>à l'intérieur</b> du
     * {@code try} — d'où le {@link org.springframework.transaction.support.TransactionTemplate}
     * explicite plutôt que l'annotation.</p>
     *
     * <p>C'est le même mécanisme que le journal de sécurité (chapitre 08 §9),
     * pour une raison opposée : là-bas pour que la trace <b>survive</b>, ici
     * pour que l'échec <b>ne contamine pas</b>.</p>
     */
    public void enregistrerVue(Long produitId, Long clientId, String sessionId,
                               String source, String adresseIp) {
        try {
            transactionIsolee.executeWithoutResult(statut ->
                    vues.save(new VueProduit(produitId, clientId, sessionId, source, adresseIp)));
        } catch (RuntimeException e) {
            // Volontairement avalé. Le seul endroit du projet où on ignore une
            // erreur — et il est justifié : la mesure ne doit jamais dégrader
            // le service qu'elle mesure.
        }
    }

    @Transactional
    public void ajouterFavori(Long clientId, Long produitId) {
        if (!favoris.existsById(new Favori.Cle(clientId, produitId))) {
            favoris.save(new Favori(clientId, produitId));
        }
    }

    @Transactional
    public void retirerFavori(Long clientId, Long produitId) {
        favoris.deleteById(new Favori.Cle(clientId, produitId));
    }

    // -------------------------------------------------------------------------
    // Agrégation nocturne
    // -------------------------------------------------------------------------

    /**
     * Résume une journée : une ligne par produit.
     *
     * <p>{@code ON CONFLICT … DO UPDATE} rend l'agrégation <b>idempotente</b> :
     * la relancer sur le même jour recalcule au lieu de dupliquer. C'est
     * indispensable — un travail nocturne échoue parfois à mi-parcours, et on
     * le relance.</p>
     *
     * <p>Le calcul est fait <b>entièrement en SQL</b>. Charger des millions de
     * lignes en mémoire pour les compter en Java serait absurde : la base sait
     * agréger, c'est son métier.</p>
     *
     * @return le nombre de produits résumés
     */
    @Transactional
    public int agregerLeJour(LocalDate jour) {
        return jdbc.update("""
                INSERT INTO statistique_produit_jour
                    (produit_id, jour, vues, vues_uniques, ajouts_panier,
                     commandes, quantite_vendue, chiffre_affaires, retours)
                SELECT p.id,
                       ?::date,
                       COALESCE(v.vues, 0),
                       COALESCE(v.vues_uniques, 0),
                       0,
                       COALESCE(c.commandes, 0),
                       COALESCE(c.quantite, 0),
                       COALESCE(c.montant, 0),
                       COALESCE(r.retours, 0)
                  FROM produit p
                  LEFT JOIN (
                        SELECT produit_id,
                               count(*)                    AS vues,
                               count(DISTINCT session_id)  AS vues_uniques
                          FROM vue_produit
                         WHERE date_heure >= ?::date AND date_heure < ?::date + 1
                         GROUP BY produit_id
                  ) v ON v.produit_id = p.id
                  LEFT JOIN (
                        SELECT va.produit_id,
                               count(DISTINCT lc.commande_id) AS commandes,
                               SUM(lc.quantite)               AS quantite,
                               SUM(lc.montant_ligne)          AS montant
                          FROM ligne_commande lc
                          JOIN variante va ON va.id = lc.variante_id
                          JOIN commande cm ON cm.id = lc.commande_id
                         WHERE cm.date_creation >= ?::date AND cm.date_creation < ?::date + 1
                         GROUP BY va.produit_id
                  ) c ON c.produit_id = p.id
                  LEFT JOIN (
                        SELECT va.produit_id, SUM(lr.quantite) AS retours
                          FROM ligne_retour lr
                          JOIN ligne_commande lc ON lc.id = lr.ligne_commande_id
                          JOIN variante va ON va.id = lc.variante_id
                          JOIN retour re ON re.id = lr.retour_id
                         WHERE re.date_creation >= ?::date AND re.date_creation < ?::date + 1
                         GROUP BY va.produit_id
                  ) r ON r.produit_id = p.id
                 WHERE COALESCE(v.vues, 0) > 0 OR COALESCE(c.commandes, 0) > 0
                    OR COALESCE(r.retours, 0) > 0
                ON CONFLICT (produit_id, jour) DO UPDATE SET
                       vues             = EXCLUDED.vues,
                       vues_uniques     = EXCLUDED.vues_uniques,
                       commandes        = EXCLUDED.commandes,
                       quantite_vendue  = EXCLUDED.quantite_vendue,
                       chiffre_affaires = EXCLUDED.chiffre_affaires,
                       retours          = EXCLUDED.retours
                """,
                Date.valueOf(jour), Date.valueOf(jour), Date.valueOf(jour),
                Date.valueOf(jour), Date.valueOf(jour),
                Date.valueOf(jour), Date.valueOf(jour));
    }

    /**
     * Supprime le détail au-delà de la rétention (D-15).
     *
     * <p>À lancer <b>après</b> l'agrégation, jamais avant : on perdrait la
     * journée qu'on s'apprêtait à résumer.</p>
     */
    @Transactional
    public int purgerLeDetail() {
        return vues.purger(Instant.now().minus(RETENTION_DETAIL));
    }

    // -------------------------------------------------------------------------
    // Lectures agrégées
    // -------------------------------------------------------------------------

    /**
     * Les produits tendance : la <b>dynamique récente</b> de la §20.
     *
     * <p>On ne classe pas par volume absolu — sinon le même best-seller
     * occuperait la première place pendant trois ans. On classe par
     * <b>progression</b> : ce qui décolle maintenant.</p>
     *
     * <p>Remarque le {@code FILTER} : il calcule deux agrégats sur deux
     * sous-ensembles différents <b>en une seule lecture</b> de la table. C'est
     * du SQL standard, peu connu, et bien plus lisible que trois
     * sous-requêtes.</p>
     */
    @Transactional(readOnly = true)
    public List<ProduitTendance> produitsTendance(int limite) {
        return jdbc.query("""
                SELECT s.produit_id,
                       p.nom,
                       COALESCE(SUM(s.quantite_vendue) FILTER (WHERE s.jour >= CURRENT_DATE - 7), 0)  AS recentes,
                       COALESCE(SUM(s.quantite_vendue) FILTER (WHERE s.jour <  CURRENT_DATE - 7), 0)  AS precedentes,
                       COALESCE(SUM(s.vues)            FILTER (WHERE s.jour >= CURRENT_DATE - 7), 0)  AS vues_recentes
                  FROM statistique_produit_jour s
                  JOIN produit p ON p.id = s.produit_id
                 WHERE s.jour >= CURRENT_DATE - 14
                 GROUP BY s.produit_id, p.nom
                HAVING SUM(s.quantite_vendue) FILTER (WHERE s.jour >= CURRENT_DATE - 7) > 0
                 ORDER BY recentes DESC
                 LIMIT ?
                """,
                (rs, i) -> {
                    long recentes = rs.getLong("recentes");
                    long precedentes = rs.getLong("precedentes");
                    // Une progression depuis zéro est infinie : on la borne,
                    // sinon un produit vendu une fois écraserait le classement.
                    double croissance = precedentes == 0
                            ? (recentes > 0 ? 2.0 : 0.0)
                            : (double) recentes / precedentes;

                    return new ProduitTendance(rs.getLong("produit_id"), rs.getString("nom"),
                            recentes, precedentes, rs.getLong("vues_recentes"), croissance);
                },
                limite);
    }

    /** Les compteurs bruts d'un produit, tous jours confondus. */
    @Transactional(readOnly = true)
    public long vuesTotales(Long produitId) {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(vues), 0) FROM statistique_produit_jour WHERE produit_id = ?
                """, Long.class, produitId);
        return total == null ? 0 : total;
    }

    @Transactional(readOnly = true)
    public long favorisDe(Long produitId) {
        return favoris.countByCleProduitId(produitId);
    }
}
