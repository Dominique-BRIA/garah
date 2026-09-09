package com.garah.api.surveillance.domaine;

import com.garah.api.commun.audit.GesteClient;
import com.garah.api.commun.securite.Acteur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Range les gestes d'un client dans SON journal.
 *
 * <h2>Le pendant de {@link EcouteurAudit}</h2>
 *
 * <p>Celui-là écarte les acteurs clients — « le parcours d'un client est une
 * autre question, qui a son propre journal ». Celui-ci écarte les acteurs
 * internes. Ensemble, ils tiennent la séparation écrite en tête de V12 :</p>
 *
 * <pre>
 * activite_client      ce que fait LE CLIENT          → comprendre son parcours
 * evenement_securite   les faits d'AUTHENTIFICATION   → détecter une anomalie
 * audit_log            les actions INTERNES           → responsabilité interne
 * </pre>
 *
 * <h2>⚠️ Écrire ce journal ne doit JAMAIS empêcher une vente</h2>
 *
 * <p>Une ligne de parcours qu'on n'arrive pas à écrire est un désagrément ;
 * une commande perdue parce qu'on n'a pas su l'écrire est une vente perdue.
 * D'où le rattrapage ci-dessous.</p>
 *
 * <p>⚠️ Il vit <b>ici</b>, et pas dans le service, et ce n'est pas un détail
 * de rangement. Le service ouvre sa propre transaction ; rattraper l'exception
 * à l'intérieur de celle-ci ne servirait à rien — elle est déjà marquée « à
 * annuler », et Spring lève un {@code UnexpectedRollback} au commit,
 * c'est-à-dire APRÈS le {@code catch}. La commande échouerait quand même.</p>
 *
 * <p>La première version de ce code faisait exactement cette erreur. Le test
 * « un client inconnu ne fait pas échouer le geste appelant » l'a attrapée.</p>
 */
@Component
public class EcouteurActiviteClient {

    private static final Logger log = LoggerFactory.getLogger(EcouteurActiviteClient.class);

    private final ServiceActiviteClient activites;

    public EcouteurActiviteClient(ServiceActiviteClient activites) {
        this.activites = activites;
    }

    @EventListener
    public void surGesteClient(GesteClient geste) {
        Acteur acteur = geste.acteur();

        // Un acteur interne ou système n'a pas de ligne `client` : la clé
        // étrangère refuserait, et ses gestes vivent de toute façon ailleurs.
        if (acteur == null || acteur.estInterne() || acteur.id() == null) {
            return;
        }

        try {
            activites.enregistrer(acteur.id(), geste.type(),
                    geste.contexte(), acteur.adresseIp());
        } catch (DataAccessException erreur) {
            // ⚠️ On trace et on continue. Le cas le plus courant : un compte
            //    sans ligne `client` — un jeton d'un environnement voisin, une
            //    reprise de données.
            log.warn("Geste client non journalise ({} pour le client {}) : {}",
                    geste.type(), acteur.id(), erreur.getMessage());
        }
    }
}
