package com.garah.api.notification.domaine;

import com.garah.api.notification.infra.AppareilNotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Les appareils abonnés aux notifications.
 *
 * <h2>⚠️ Déclarer un appareil est IDEMPOTENT</h2>
 *
 * <p>L'application redéclare son jeton à chaque démarrage, à chaque
 * reconnexion, et chaque fois que Google le renouvelle. Créer une ligne à
 * chaque appel donnerait des milliers de doublons — et autant d'envois
 * facturés pour une seule notification.</p>
 */
@Service
public class ServiceAppareils {

    /**
     * Au-delà, on considère l'application désinstallée.
     *
     * <p>Soixante jours et non trente : un client qui ne commande qu'à chaque
     * trimestre n'a pas désinstallé, il n'a simplement rien à faire ici. Le
     * radier lui ferait manquer la notification qui compte — celle de la
     * marchandise arrivée.</p>
     */
    private static final int JOURS_AVANT_OUBLI = 60;

    private final AppareilNotificationRepository appareils;

    public ServiceAppareils(AppareilNotificationRepository appareils) {
        this.appareils = appareils;
    }

    /**
     * Déclare — ou redéclare — un appareil.
     *
     * <p>Si le jeton existe déjà, il change simplement de propriétaire : c'est
     * le cas du téléphone partagé, et il ne doit pas créer une seconde ligne.</p>
     */
    @Transactional
    public void declarer(String jeton, Long utilisateurId, String plateforme) {
        appareils.findById(jeton)
                .ifPresentOrElse(
                        a -> a.rattacherA(utilisateurId, plateforme),
                        () -> appareils.save(
                                new AppareilNotification(jeton, utilisateurId, plateforme)));
    }

    /**
     * Retire un appareil — à la déconnexion.
     *
     * <p>⚠️ On vérifie que le jeton appartient bien à l'appelant. Sans ce
     * contrôle, n'importe quel compte connecté désabonnerait le téléphone de
     * n'importe qui : il suffirait de deviner un jeton, et le client cesserait
     * d'être prévenu sans jamais comprendre pourquoi.</p>
     */
    @Transactional
    public void retirer(String jeton, Long utilisateurId) {
        appareils.findById(jeton)
                .filter(a -> a.getUtilisateurId().equals(utilisateurId))
                .ifPresent(appareils::delete);
    }

    @Transactional(readOnly = true)
    public List<AppareilNotification> de(Long utilisateurId) {
        return appareils.findByUtilisateurId(utilisateurId);
    }

    /** Le ménage des jetons morts. */
    @Transactional
    public long oublierLesInactifs() {
        return appareils.deleteByDateMajBefore(
                Instant.now().minus(JOURS_AVANT_OUBLI, ChronoUnit.DAYS));
    }
}
