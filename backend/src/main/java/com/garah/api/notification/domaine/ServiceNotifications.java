package com.garah.api.notification.domaine;

import com.garah.api.notification.infra.AppareilNotificationRepository;
import com.garah.api.notification.infra.PasserelleFcm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Envoyer une notification à quelqu'un.
 *
 * <h2>🎯 Trois moments pour le client, et trois seulement</h2>
 *
 * <ul>
 *   <li>la <b>marchandise est arrivée</b> — c'est là qu'on donne le code de
 *       retrait ;</li>
 *   <li>un <b>conseiller a répondu</b> ;</li>
 *   <li>une <b>proposition de prix</b> a été faite ou acceptée.</li>
 * </ul>
 *
 * <p>Le reste — commande payée, colis parti — se voit déjà dans l'application.
 * Une notification pour chacun apprend à les ignorer toutes, et le jour où la
 * marchandise arrive vraiment, plus personne ne regarde.</p>
 *
 * <h2>⚠️ L'envoi est ASYNCHRONE, et son échec ne remonte jamais</h2>
 *
 * <p>Une notification part vers Google : c'est un aller-retour réseau, qui peut
 * durer ou échouer. La faire porter par la transaction métier ferait échouer
 * une <b>remise de marchandise</b> parce qu'un téléphone est éteint. Le geste
 * réussit d'abord ; la notification suit.</p>
 *
 * <h2>⚠️ Un jeton mort est RADIÉ, pas réessayé</h2>
 *
 * <p>Application désinstallée, jeton renouvelé : le garder ferait un envoi
 * facturé qui échoue à chaque fois, indéfiniment. Google le dit clairement
 * ({@code UNREGISTERED}) et on l'écoute.</p>
 */
@Service
public class ServiceNotifications {

    private static final Logger JOURNAL = LoggerFactory.getLogger(ServiceNotifications.class);

    private final AppareilNotificationRepository appareils;
    private final PasserelleFcm passerelle;

    public ServiceNotifications(AppareilNotificationRepository appareils,
                                PasserelleFcm passerelle) {
        this.appareils = appareils;
        this.passerelle = passerelle;
    }

    /**
     * Prévenir UNE personne.
     *
     * <p>Sur tous ses appareils : quelqu'un a souvent un téléphone et un
     * navigateur, et deviner lequel il regarde reviendrait à ne pas le prévenir
     * une fois sur deux.</p>
     */
    @Async("executeurNotifications")
    @Transactional
    public void prevenir(Long utilisateurId, String titre, String corps,
                         Map<String, String> donnees) {
        envoyerA(appareils.findByUtilisateurId(utilisateurId), titre, corps, donnees);
    }

    /**
     * Prévenir PLUSIEURS personnes — un rôle, une équipe.
     *
     * <p>C'est le cas du signal d'équipe : « une réclamation attend depuis trop
     * longtemps » ne s'adresse à personne en particulier, mais à quiconque peut
     * s'en saisir.</p>
     *
     * <p>⚠️ UNE requête pour tous les appareils, pas une par destinataire. Une
     * équipe de vingt donnerait vingt requêtes pour un seul événement.</p>
     */
    @Async("executeurNotifications")
    @Transactional
    public void prevenirTous(Collection<Long> utilisateurIds, String titre, String corps,
                             Map<String, String> donnees) {
        if (utilisateurIds.isEmpty()) {
            return;
        }
        envoyerA(appareils.findByUtilisateurIdIn(utilisateurIds), titre, corps, donnees);
    }

    private void envoyerA(List<AppareilNotification> cibles, String titre, String corps,
                          Map<String, String> donnees) {
        for (AppareilNotification appareil : cibles) {
            try {
                if (!passerelle.envoyer(appareil.getJeton(), titre, corps, donnees)) {
                    // Le jeton est mort : on le radie plutôt que de le
                    // réessayer à chaque événement pendant des mois.
                    appareils.delete(appareil);
                }
            } catch (Exception e) {
                // Un appareil qui échoue ne doit pas empêcher les autres d'être
                // prévenus : c'est souvent celui d'un collègue en congé.
                JOURNAL.warn("Notification non delivree a un appareil.", e);
            }
        }
    }
}
