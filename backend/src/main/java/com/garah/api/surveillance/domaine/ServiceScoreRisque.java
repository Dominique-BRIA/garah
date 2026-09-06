package com.garah.api.surveillance.domaine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.surveillance.infra.AlerteSecuriteRepository;
import com.garah.api.surveillance.infra.ScoreRisqueClientRepository;
import com.garah.api.surveillance.infra.SignauxRisqueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Le calcul du score de risque.
 *
 * <p><b>Règle fondatrice n°2 : la surveillance observe, elle ne décide pas.</b>
 * Ce service ne bloque aucun compte, n'annule aucune commande, ne refuse aucun
 * paiement. Il constate, il explique, et il alerte un humain.</p>
 *
 * <p>Un score {@code HIGH} signifie « risque élevé », <b>pas</b> « client
 * frauduleux ». La spécification (§19) était explicite là-dessus, et c'est une
 * distinction qui protège autant l'entreprise que le client.</p>
 */
@Service
public class ServiceScoreRisque {

    /**
     * La version du calcul.
     *
     * <p>⚠️ <b>À incrémenter à CHAQUE modification des signaux ou des poids.</b>
     * Sans ça, comparer le score d'hier et celui d'aujourd'hui n'aurait aucun
     * sens — et une décision prise sur cette comparaison serait arbitraire.</p>
     */
    private static final String VERSION = "v1.0";

    private static final Duration FENETRE_LONGUE = Duration.ofHours(24);
    private static final Duration FENETRE_COURTE = Duration.ofMinutes(10);

    private final SignauxRisqueRepository signaux;
    private final ScoreRisqueClientRepository scores;
    private final AlerteSecuriteRepository alertes;
    private final ObjectMapper json;

    public ServiceScoreRisque(SignauxRisqueRepository signaux,
                              ScoreRisqueClientRepository scores,
                              AlerteSecuriteRepository alertes,
                              ObjectMapper json) {
        this.signaux = signaux;
        this.scores = scores;
        this.alertes = alertes;
        this.json = json;
    }

    /**
     * Évalue un client, et explique le résultat.
     *
     * <p>Chaque signal est <b>plafonné</b> individuellement : un seul indice,
     * même extrême, ne doit pas suffire à faire basculer un client en
     * {@code CRITICAL}. C'est l'<b>accumulation</b> de signaux différents qui
     * fait le risque, pas l'intensité d'un seul.</p>
     *
     * <p>Sans ce plafonnement, un client qui se trompe vingt fois de mot de
     * passe — ce qui arrive — serait traité comme un fraudeur.</p>
     */
    @Transactional(readOnly = true)
    public EvaluationRisque evaluer(Long clientId) {
        Instant depuis24h = Instant.now().minus(FENETRE_LONGUE);
        Instant depuis10min = Instant.now().minus(FENETRE_COURTE);

        List<SignalRisque> releves = new ArrayList<>();

        ajouter(releves, "ECHECS_CONNEXION",
                signaux.echecsConnexion(clientId, depuis24h),
                4, 25, n -> n + " échec(s) de connexion en 24 h");

        ajouter(releves, "ECHECS_PAIEMENT",
                signaux.echecsPaiement(clientId, depuis24h),
                8, 30, n -> n + " paiement(s) refusé(s) en 24 h");

        ajouter(releves, "NOUVEL_APPAREIL",
                signaux.nouveauxAppareils(clientId, depuis24h),
                15, 15, n -> n + " appareil(s) inconnu(s) en 24 h");

        ajouter(releves, "VITESSE_COMMANDE",
                signaux.commandesRecentes(clientId, depuis10min),
                3, 15, n -> n + " commande(s) en 10 minutes");

        ajouter(releves, "ANNULATIONS",
                signaux.annulationsRecentes(clientId, depuis24h),
                5, 15, n -> n + " commande(s) annulée(s) en 24 h");

        double total = Math.min(100, releves.stream().mapToDouble(SignalRisque::poids).sum());

        return new EvaluationRisque(total, NiveauRisque.pour(total), VERSION, releves);
    }

    /**
     * Évalue, enregistre, et alerte si nécessaire.
     *
     * <p>Une nouvelle ligne à chaque calcul : on n'écrase pas l'historique.
     * Savoir qu'un client était à 78 le 6 septembre et à 12 le 20 raconte
     * quelque chose que la valeur courante ne dit pas.</p>
     */
    @Transactional
    public ScoreRisqueClient calculerEtEnregistrer(Long clientId) {
        EvaluationRisque evaluation = evaluer(clientId);

        ScoreRisqueClient enregistre = scores.save(new ScoreRisqueClient(
                clientId,
                BigDecimal.valueOf(evaluation.score()).setScale(2, RoundingMode.HALF_UP),
                evaluation.niveau(),
                evaluation.version(),
                serialiser(evaluation.signaux())));

        // Une alerte seulement à partir de HIGH, et une seule à la fois :
        // recalculer le score toutes les heures ne doit pas produire vingt
        // alertes pour la même situation.
        if (evaluation.niveau() == NiveauRisque.HIGH
                || evaluation.niveau() == NiveauRisque.CRITICAL) {
            ouvrirAlerteSiNecessaire(clientId, evaluation);
        }

        return enregistre;
    }

    private void ouvrirAlerteSiNecessaire(Long clientId, EvaluationRisque evaluation) {
        boolean dejaOuverte = alertes.existsByClientIdAndTypeAndStatutIn(
                clientId, "SCORE_RISQUE", List.of("OUVERTE", "EN_COURS"));

        if (!dejaOuverte) {
            alertes.save(new AlerteSecurite(clientId, "SCORE_RISQUE",
                    evaluation.niveau() == NiveauRisque.CRITICAL
                            ? GraviteEvenement.CRITIQUE
                            : GraviteEvenement.HAUTE,
                    null));
        }
    }

    /**
     * Un humain tranche.
     *
     * <p>La décision est <b>obligatoire</b>. Une alerte traitée sans décision
     * écrite ne sert à rien : personne ne saura si le compte a été vérifié ou
     * simplement classé pour vider la file.</p>
     */
    @Transactional
    public AlerteSecurite trancher(Long alerteId, boolean confirmee,
                                   Long responsableId, String decision) {
        AlerteSecurite alerte = alertes.findById(alerteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Alerte", alerteId));

        if (decision == null || decision.isBlank()) {
            throw new com.garah.api.commun.erreur.RegleMetierViolee("DECISION_OBLIGATOIRE",
                    "Une alerte se clôt avec une décision écrite.");
        }
        if (!alerte.estOuverte()) {
            throw new com.garah.api.commun.erreur.ConflitEtat("ALERTE_CLOSE",
                    "Cette alerte a déjà été traitée.");
        }

        alerte.traiter(confirmee ? "TRAITEE" : "IGNOREE", responsableId, decision);
        return alerte;
    }

    @Transactional(readOnly = true)
    public List<AlerteSecurite> fileDAlertes() {
        return alertes.aTraiter();
    }

    @Transactional(readOnly = true)
    public List<ScoreRisqueClient> historique(Long clientId) {
        return scores.findByClientIdOrderByDateCalculDesc(clientId);
    }

    // -------------------------------------------------------------------------

    /**
     * Ajoute un signal, avec un poids proportionnel et plafonné.
     *
     * @param parUnite poids de chaque occurrence
     * @param plafond  contribution maximale de ce signal
     */
    private void ajouter(List<SignalRisque> releves, String code, long valeur,
                         double parUnite, double plafond,
                         java.util.function.LongFunction<String> libelle) {
        if (valeur <= 0) {
            return;   // un signal absent n'a pas à figurer dans l'explication
        }
        double poids = Math.min(plafond, valeur * parUnite);
        releves.add(new SignalRisque(code, libelle.apply(valeur), valeur, poids));
    }

    private String serialiser(List<SignalRisque> signaux) {
        try {
            return json.writeValueAsString(Map.of("signaux", signaux));
        } catch (JsonProcessingException e) {
            // Un score sans explication ne vaut rien, mais il vaut mieux
            // l'enregistrer avec une explication vide que de perdre le calcul.
            return "{\"signaux\":[],\"erreur\":\"serialisation\"}";
        }
    }
}
