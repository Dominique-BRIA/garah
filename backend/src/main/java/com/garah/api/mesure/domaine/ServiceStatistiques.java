package com.garah.api.mesure.domaine;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.mesure.infra.FavoriRepository;
import com.garah.api.mesure.infra.VueProduitRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * Les produits que J'AI mis en favori, du plus récent au plus ancien.
     *
     * <h2>⚠️ Elle rend des IDENTIFIANTS, pas des produits</h2>
     *
     * <p>Et ce n'est pas une paresse : {@code mesure} ne connaît pas l'entité
     * {@code Produit}, et ne doit pas la connaître. Compter des vues et tenir
     * une liste d'envies n'est pas le métier du catalogue — les lier ferait
     * dépendre la mesure de ce qu'elle mesure.</p>
     *
     * <p>L'appelant complète avec {@code GET /api/produits/par-ids}, qui rend
     * les vignettes en une seule requête. Deux appels pour un écran, jamais un
     * par ligne.</p>
     */
    @Transactional(readOnly = true)
    public List<Long> mesFavoris(Long clientId) {
        return favoris.findByCleClientIdOrderByDateAjoutDesc(clientId).stream()
                .map(f -> f.getCle().getProduitId())
                .toList();
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
        Date d = Date.valueOf(jour);
        int produits = jdbc.update("""
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
                          JOIN (%s) payee ON payee.commande_id = lc.commande_id
                         WHERE payee.payee_le >= ?::date AND payee.payee_le < ?::date + 1
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
                """.formatted(COMMANDES_PAYEES), d, d, d, d, d, d, d);

        // 🎯 LA JOURNEE EST NOTEE, MEME VIDE.
        //
        //    Les lignes ci-dessus n'existent que pour les produits qui ont
        //    bouge. Une journee sans visite ni vente n'en laissait AUCUNE — et
        //    l'ecran, qui comptait les jours a partir d'elles, annoncait « ces
        //    jours-la n'ont pas ete resumes » pour des jours simplement calmes.
        //    Il ne pouvait pas distinguer une nuit manquee d'une journee vide.
        //
        //    L'argent se lit ICI, a la journee : l'encaisse comprend les frais
        //    d'acheminement, que les lignes par produit ne portent pas.
        jdbc.update("""
                INSERT INTO journee_resumee
                    (jour, date_resume, montant_encaisse, montant_rembourse,
                     commandes_payees, commandes_annulees)
                SELECT ?::date, now(),
                       COALESCE((SELECT SUM(montant) FROM paiement
                                  WHERE type = 'ENCAISSEMENT' AND statut = 'CONFIRME'
                                    AND date_confirmation >= ?::date
                                    AND date_confirmation < ?::date + 1), 0),
                       COALESCE((SELECT SUM(montant) FROM paiement
                                  WHERE type = 'REMBOURSEMENT' AND statut = 'CONFIRME'
                                    AND date_confirmation >= ?::date
                                    AND date_confirmation < ?::date + 1), 0),
                       (SELECT count(*) FROM (%s) payee
                         WHERE payee.payee_le >= ?::date AND payee.payee_le < ?::date + 1),
                       (SELECT count(*) FROM commande
                         WHERE date_annulation >= ?::date AND date_annulation < ?::date + 1)
                ON CONFLICT (jour) DO UPDATE SET
                       date_resume        = now(),
                       montant_encaisse   = EXCLUDED.montant_encaisse,
                       montant_rembourse  = EXCLUDED.montant_rembourse,
                       commandes_payees   = EXCLUDED.commandes_payees,
                       commandes_annulees = EXCLUDED.commandes_annulees
                """.formatted(COMMANDES_PAYEES), d, d, d, d, d, d, d, d, d);

        return produits;
    }

    /**
     * Les commandes PAYÉES, et le jour où elles l'ont été.
     *
     * <h2>🎯 Une vente, c'est de l'argent encaissé — pas une commande créée</h2>
     *
     * <p>Le résumé comptait toute commande <b>créée</b> dans la journée, quel
     * que soit son statut : impayées, annulées et expirées gonflaient le
     * chiffre d'affaires. Et une commande créée lundi, payée mercredi, était
     * vendue lundi.</p>
     *
     * <p>Une commande est payée quand elle a quitté l'attente de paiement ; son
     * jour est celui du DERNIER encaissement confirmé — celui qui l'a soldée.
     * Une commande payée puis annulée reste vendue ce jour-là : l'argent est
     * entré. Son remboursement se compte à part, le jour où il sort.</p>
     */
    private static final String COMMANDES_PAYEES = """
            SELECT pa.commande_id, max(pa.date_confirmation) AS payee_le
              FROM paiement pa
              JOIN commande cm ON cm.id = pa.commande_id
             WHERE pa.type = 'ENCAISSEMENT' AND pa.statut = 'CONFIRME'
               AND cm.statut <> 'EN_ATTENTE_PAIEMENT'
             GROUP BY pa.commande_id
            """;

    /**
     * Résume les journées de la période qui ne l'ont jamais été.
     *
     * <h2>🎯 Rattraper TOUTES les nuits manquées, pas seulement hier</h2>
     *
     * <p>Le bouton ne relançait que la veille. Une semaine de serveur endormi
     * laissait six jours que rien ne permettait de récupérer — alors que les
     * commandes, les paiements et les retours, eux, sont toujours là.</p>
     *
     * <p>⚠️ <b>Jamais au-delà de la rétention du détail des vues.</b> Passé ce
     * délai, le détail est purgé : une journée jamais résumée afficherait zéro
     * visite là où il y en a eu. On ne rattrape que ce qu'on peut encore
     * compter juste. Jamais aujourd'hui non plus : la journée n'est pas
     * finie.</p>
     *
     * @return le nombre de journées résumées
     */
    @Transactional
    public int rattraper(LocalDate du, LocalDate au) {
        LocalDate aujourdhui = LocalDate.now(java.time.ZoneId.of("Africa/Douala"));
        LocalDate plusAncien = aujourdhui.minusDays(RETENTION_DETAIL.toDays() - 2);
        LocalDate debut = du.isBefore(plusAncien) ? plusAncien : du;
        LocalDate fin = au.isBefore(aujourdhui) ? au : aujourdhui.minusDays(1);
        if (debut.isAfter(fin)) {
            return 0;
        }

        java.util.Set<LocalDate> dejaResumees = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT jour FROM journee_resumee WHERE jour BETWEEN ? AND ?",
                LocalDate.class, debut, fin));

        int resumees = 0;
        for (LocalDate jour = debut; !jour.isAfter(fin); jour = jour.plusDays(1)) {
            if (!dejaResumees.contains(jour)) {
                agregerLeJour(jour);
                resumees++;
            }
        }
        return resumees;
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

    // -------------------------------------------------------------------------
    // Le bilan d'une période
    // -------------------------------------------------------------------------

    /**
     * Ce que le catalogue a produit entre deux dates.
     *
     * <h2>Lu dans les agrégats, jamais recalculé depuis le détail</h2>
     *
     * <p>🎯 {@code vue_produit} est <b>purgé</b> à 90 jours (D-15). Recalculer
     * un bilan depuis le détail donnerait donc des chiffres justes sur les
     * dernières semaines et faux au-delà, sans que rien ne signale la
     * bascule — le pire des deux mondes.</p>
     *
     * <p>Trois requêtes, quelle que soit la période : les totaux, le
     * classement, la courbe. Les agréger en Java demanderait de ramener une
     * ligne par produit et par jour, soit des dizaines de milliers de lignes
     * pour afficher six chiffres.</p>
     */
    @Transactional(readOnly = true)
    public BilanPeriode bilan(LocalDate du, LocalDate au, int limite) {
        if (du.isAfter(au)) {
            throw new RegleMetierViolee("PERIODE_INVALIDE",
                    "La date de début doit précéder la date de fin.");
        }

        // L'ARGENT et les JOURS se lisent sur les journees resumees : c'est la
        // seule table qui sait qu'une journee a ete resumee, meme vide, et la
        // seule qui porte l'encaisse, frais d'acheminement compris.
        record Argent(int jours, long payees, long annulees,
                      BigDecimal encaisse, BigDecimal rembourse) {
        }
        Argent argent = jdbc.queryForObject("""
                SELECT count(*)                              AS jours,
                       COALESCE(SUM(commandes_payees), 0)   AS payees,
                       COALESCE(SUM(commandes_annulees), 0) AS annulees,
                       COALESCE(SUM(montant_encaisse), 0)   AS encaisse,
                       COALESCE(SUM(montant_rembourse), 0)  AS rembourse
                  FROM journee_resumee
                 WHERE jour BETWEEN ? AND ?
                """,
                (rs, i) -> new Argent(rs.getInt("jours"), rs.getLong("payees"),
                        rs.getLong("annulees"), rs.getBigDecimal("encaisse"),
                        rs.getBigDecimal("rembourse")),
                du, au);

        record Activite(long vues, long vuesUniques, long quantite, long retours) {
        }
        Activite activite = jdbc.queryForObject("""
                SELECT COALESCE(SUM(vues), 0)            AS vues,
                       COALESCE(SUM(vues_uniques), 0)    AS vues_uniques,
                       COALESCE(SUM(quantite_vendue), 0) AS quantite,
                       COALESCE(SUM(retours), 0)         AS retours
                  FROM statistique_produit_jour
                 WHERE jour BETWEEN ? AND ?
                """,
                (rs, i) -> new Activite(rs.getLong("vues"), rs.getLong("vues_uniques"),
                        rs.getLong("quantite"), rs.getLong("retours")),
                du, au);

        List<BilanPeriode.LigneBilan> meilleurs = jdbc.query("""
                SELECT s.produit_id,
                       p.nom,
                       SUM(s.vues)             AS vues,
                       SUM(s.commandes)        AS commandes,
                       SUM(s.quantite_vendue)  AS quantite,
                       SUM(s.chiffre_affaires) AS montant
                  FROM statistique_produit_jour s
                  JOIN produit p ON p.id = s.produit_id
                 WHERE s.jour BETWEEN ? AND ?
                 GROUP BY s.produit_id, p.nom
                HAVING SUM(s.vues) > 0 OR SUM(s.commandes) > 0
                 ORDER BY montant DESC, commandes DESC
                 LIMIT ?
                """,
                (rs, i) -> {
                    long vues = rs.getLong("vues");
                    long commandes = rs.getLong("commandes");
                    // Nul, et non zéro : un taux calculé sur zéro vue
                    // accuserait à tort une fiche que personne n'a ouverte.
                    Double taux = vues == 0 ? null : (double) commandes / vues;

                    return new BilanPeriode.LigneBilan(
                            rs.getLong("produit_id"), rs.getString("nom"),
                            vues, commandes, rs.getLong("quantite"),
                            rs.getBigDecimal("montant"), taux);
                },
                du, au, limite);

        // Une barre par journee RESUMEE, meme a zero : une journee calme se
        // voit comme une barre basse, pas comme un trou qu'on prendrait pour
        // une panne.
        List<BilanPeriode.PointJour> courbe = jdbc.query("""
                SELECT j.jour,
                       COALESCE((SELECT SUM(s.vues) FROM statistique_produit_jour s
                                  WHERE s.jour = j.jour), 0) AS vues,
                       j.commandes_payees                     AS commandes,
                       j.montant_encaisse                     AS montant
                  FROM journee_resumee j
                 WHERE j.jour BETWEEN ? AND ?
                 ORDER BY j.jour ASC
                """,
                (rs, i) -> new BilanPeriode.PointJour(
                        rs.getObject("jour", LocalDate.class),
                        rs.getLong("vues"), rs.getLong("commandes"),
                        rs.getBigDecimal("montant")),
                du, au);

        return new BilanPeriode(du, au, argent.jours(), activite.vues(), activite.vuesUniques(),
                argent.payees(), argent.annulees(), activite.quantite(),
                argent.encaisse(), argent.rembourse(), activite.retours(),
                meilleurs, courbe);
    }
}
