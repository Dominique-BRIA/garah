package com.garah.api.planification;

import com.garah.api.commerce.domaine.ServiceCommande;
import com.garah.api.commerce.domaine.ServicePaiementMobile;
import com.garah.api.iam.domaine.ServiceRafraichissement;
import com.garah.api.iam.domaine.ServiceVerificationEmail;
import com.garah.api.mesure.domaine.ServiceStatistiques;
import com.garah.api.serviceclient.domaine.ServiceNegociation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Les traitements que personne ne déclenche — et sans lesquels le système ment.
 *
 * <p><b>Ces cinq méthodes existaient déjà, écrites et testées.</b> Aucune
 * n'était appelée : il n'y avait ni {@code @EnableScheduling} ni
 * {@code @Scheduled} dans le projet. Le résultat n'était pas une panne visible,
 * mais quelque chose de pire — un système qui a l'air de marcher :</p>
 *
 * <table border="1">
 *   <caption>Ce qui se passait sans ce fichier</caption>
 *   <tr><th>Traitement</th><th>Conséquence de son absence</th></tr>
 *   <tr><td>libération des impayées</td>
 *       <td>le stock disponible fond ; chaque paiement abandonné immobilise
 *           sa marchandise <b>définitivement</b> (D-06)</td></tr>
 *   <tr><td>réconciliation des paiements</td>
 *       <td>un webhook perdu = un client débité dont la commande n'est jamais
 *           payée</td></tr>
 *   <tr><td>expiration des propositions</td>
 *       <td>un prix négocié il y a six mois reste acceptable</td></tr>
 *   <tr><td>agrégation des statistiques</td>
 *       <td>{@code statistique_produit_jour} reste vide : aucun tableau de
 *           bord, aucun produit tendance (D-15)</td></tr>
 *   <tr><td>purge du détail</td>
 *       <td>{@code vue_produit} grossit sans fin — la bombe que D-15 décrit
 *           lui-même</td></tr>
 * </table>
 *
 * <h2>⚠️ Une seule instance</h2>
 *
 * <p>Ces tâches supposent <b>un seul processus</b>. C'est vrai sur l'offre
 * gratuite de Render (D-14), et ça cesse de l'être à la seconde instance :
 * deux serveurs agrégeraient les mêmes statistiques deux fois.</p>
 *
 * <p>Le jour de la mise à l'échelle, il faudra un verrou partagé
 * (ShedLock sur une table PostgreSQL est le plus simple, puisque la base est
 * déjà là). <b>C'est à faire avant d'ajouter une instance, pas après</b> :
 * le double comptage ne produit aucune erreur, seulement des chiffres faux.</p>
 *
 * <h2>Sur les fuseaux horaires</h2>
 *
 * <p>Les tâches de nuit sont ancrées sur {@code Africa/Douala}, pas sur le
 * fuseau du serveur. {@code TZ=UTC} est fixé sur Render : sans zone explicite,
 * « 2 h du matin » tomberait à 3 h locales — et la purge s'exécuterait pendant
 * les heures creuses de quelqu'un d'autre.</p>
 */
@Component
@ConditionalOnProperty(name = "garah.planification.active", havingValue = "true",
                       matchIfMissing = true)
public class TachesPeriodiques {

    private static final Logger log = LoggerFactory.getLogger(TachesPeriodiques.class);

    /** Le fuseau du métier. GARAH opère entre Douala et Bangui, tous deux en UTC+1. */
    private static final String FUSEAU = "Africa/Douala";

    private final ServiceCommande commandes;
    private final ServicePaiementMobile paiements;
    private final ServiceNegociation negociation;
    private final ServiceStatistiques statistiques;
    private final ServiceRafraichissement sessions;
    private final ServiceVerificationEmail verification;

    public TachesPeriodiques(ServiceCommande commandes,
                             ServicePaiementMobile paiements,
                             ServiceNegociation negociation,
                             ServiceStatistiques statistiques,
                             ServiceRafraichissement sessions,
                             ServiceVerificationEmail verification) {
        this.commandes = commandes;
        this.paiements = paiements;
        this.negociation = negociation;
        this.statistiques = statistiques;
        this.sessions = sessions;
        this.verification = verification;
    }

    /**
     * Rattrape les paiements dont la notification s'est perdue.
     *
     * <p>Toutes les deux minutes, parce que c'est le délai que <b>le client</b>
     * ressent : il a validé sur son téléphone et regarde une page qui dit
     * « en attente ». Plus lent serait perçu comme une panne.</p>
     *
     * <p>Ne coûte rien quand il n'y a rien à faire : la requête ne remonte que
     * les paiements {@code EN_ATTENTE} qui portent une référence.</p>
     */
    @Scheduled(fixedDelay = 2, timeUnit = java.util.concurrent.TimeUnit.MINUTES,
               initialDelay = 1)
    public void reconcilierLesPaiements() {
        executer("reconciliation des paiements", () -> paiements.reconcilier());
    }

    /**
     * Libère le stock des commandes jamais payées (D-06).
     *
     * <p>{@code fixedDelay} et non {@code fixedRate} : on attend dix minutes
     * <b>après la fin</b> du traitement précédent. Avec {@code fixedRate}, une
     * exécution qui dépasse dix minutes verrait la suivante démarrer par-dessus
     * — deux traitements libérant le même stock en parallèle.</p>
     */
    @Scheduled(fixedDelay = 10, timeUnit = java.util.concurrent.TimeUnit.MINUTES,
               initialDelay = 3)
    public void libererLesCommandesImpayees() {
        executer("liberation des commandes impayees", commandes::libererLesImpayees);
    }

    /**
     * Referme les propositions de prix dépassées.
     *
     * <p>Une fois par heure suffit : une proposition expirée ne peut de toute
     * façon plus être consommée (le service le vérifie). Ce traitement met
     * l'affichage en accord avec la règle, il ne la fait pas respecter.</p>
     */
    @Scheduled(cron = "0 5 * * * *", zone = FUSEAU)
    public void expirerLesPropositions() {
        executer("expiration des propositions de prix", negociation::expirerLesDepassees);
    }

    /**
     * Agrège les vues de la veille (D-15).
     *
     * <p><b>La veille, jamais le jour courant.</b> Agréger aujourd'hui à 1 h du
     * matin figerait un total incomplet, et le jour se terminerait sans que
     * personne ne recompte.</p>
     *
     * <p>Le service est idempotent par (produit, jour) : rejouer la même
     * journée écrase proprement. C'est indispensable — un redémarrage pendant
     * la nuit doit pouvoir être rattrapé à la main.</p>
     */
    @Scheduled(cron = "0 0 1 * * *", zone = FUSEAU)
    public void agregerLesStatistiques() {
        LocalDate hier = LocalDate.now(java.time.ZoneId.of(FUSEAU)).minusDays(1);
        executer("agregation des statistiques du " + hier,
                () -> statistiques.agregerLeJour(hier));
    }

    /**
     * Purge le détail des vues au-delà de 90 jours (D-15).
     *
     * <p>⚠️ <b>Une heure APRÈS l'agrégation, jamais avant.</b> Purger d'abord
     * détruirait les lignes qu'on s'apprêtait à compter — et la perte serait
     * définitive et silencieuse.</p>
     *
     * <p>L'écart d'une heure est une marge, pas une garantie. Le vrai filet est
     * que la purge porte sur 90 jours et l'agrégation sur la veille : même
     * inversées, elles ne se croisent pas.</p>
     */
    @Scheduled(cron = "0 0 2 * * *", zone = FUSEAU)
    public void purgerLeDetailDesVues() {
        executer("purge du detail des vues", statistiques::purgerLeDetail);
    }

    /**
     * Purge les jetons de rafraîchissement expirés (D-19).
     *
     * <p>La table grossit vite : un jeton par connexion, <b>plus un de plus
     * toutes les 15 minutes</b> pour chaque session active, à cause de la
     * rotation. Une centaine d'utilisateurs connectés produit près de dix
     * mille lignes par jour.</p>
     *
     * <p>C'est le même piège que {@code vue_produit} (D-15), en beaucoup plus
     * rapide — d'où une purge quotidienne dès le premier jour, et non « plus
     * tard ».</p>
     *
     * <p>Le service garde une marge de sept jours après expiration : les
     * lignes récentes servent à comprendre un incident (« ce jeton a-t-il été
     * réutilisé ? »).</p>
     */
    @Scheduled(cron = "0 30 2 * * *", zone = FUSEAU)
    public void purgerLesJetonsExpires() {
        executer("purge des jetons de rafraichissement", sessions::purger);
    }

    /**
     * Purge les jetons de confirmation d'adresse expirés (D-23).
     *
     * <p>Une ligne par inscription et par renvoi. Moins volumineux que les
     * jetons de rafraîchissement, mais la table ne redescend jamais toute
     * seule — c'est la leçon de {@code vue_produit} (D-15), et elle vaut pour
     * toute table qui ne fait que grossir.</p>
     */
    @Scheduled(cron = "0 40 2 * * *", zone = FUSEAU)
    public void purgerLesJetonsDeVerification() {
        executer("purge des jetons de verification", verification::purger);
    }

    /**
     * Exécute une tâche sans jamais laisser une exception remonter.
     *
     * <p>🎯 <b>C'est le point le plus important de ce fichier.</b> Une
     * exception qui s'échappe d'une méthode {@code @Scheduled} avec
     * {@code fixedDelay} n'arrête pas seulement cette exécution : selon le
     * planificateur, elle peut <b>annuler définitivement la tâche</b>. Le
     * serveur continue de tourner, plus rien n'est libéré, et aucune alerte ne
     * se déclenche — la panne la plus silencieuse qui soit.</p>
     */
    private void executer(String libelle, TravailPeriodique travail) {
        long debut = System.currentTimeMillis();
        try {
            int touches = travail.executer();

            // On ne journalise que ce qui a eu un effet : une ligne « 0 element »
            // toutes les deux minutes noierait le journal, et c'est exactement
            // ainsi qu'on cesse de le lire.
            if (touches > 0) {
                log.info("Tache '{}' : {} element(s) traite(s) en {} ms",
                        libelle, touches, System.currentTimeMillis() - debut);
            }
        } catch (Exception e) {
            log.error("Tache '{}' en echec — elle sera retentee a la prochaine execution",
                    libelle, e);
        }
    }

    @FunctionalInterface
    private interface TravailPeriodique {
        int executer();
    }
}
