package com.garah.api.surveillance.domaine;

import com.garah.api.surveillance.infra.EvenementSecuriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Enregistre les faits de sécurité.
 *
 * <p>Rappel de la règle fondatrice n°2 (chapitre 01) : <b>la surveillance
 * observe, elle ne décide pas</b>. Ce service n'a aucune méthode qui bloque un
 * compte. Il constate, et un humain tranchera.</p>
 */
@Service
public class ServiceEvenementsSecurite {

    /** Au-delà, on considère la série d'échecs comme anormale. */
    private static final long SEUIL_ECHECS = 5;
    private static final Duration FENETRE_ECHECS = Duration.ofHours(1);

    private final EvenementSecuriteRepository evenements;

    public ServiceEvenementsSecurite(EvenementSecuriteRepository evenements) {
        this.evenements = evenements;
    }

    /**
     * {@code REQUIRES_NEW} : l'événement est enregistré dans sa PROPRE
     * transaction.
     *
     * <p>Sans ça, un échec de connexion qui remonte une exception annulerait
     * la transaction appelante — et effacerait la trace de l'échec au moment
     * même où on en a le plus besoin. Le journal de sécurité doit survivre à
     * ce qu'il journalise.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void enregistrer(Long utilisateurId, TypeEvenementSecurite type,
                            GraviteEvenement gravite, String adresseIp, String description) {
        evenements.save(new EvenementSecurite(utilisateurId, type, gravite, adresseIp, description));
    }

    /**
     * Enregistre un échec de connexion, en montant la gravité quand ils
     * s'accumulent.
     *
     * <p>Un échec isolé est banal — on se trompe de mot de passe. Six échecs
     * en une heure ne le sont pas. C'est la <b>répétition</b> qui fait le
     * signal, pas l'événement.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void enregistrerEchecConnexion(Long utilisateurId, String adresseIp, String raison) {
        GraviteEvenement gravite = GraviteEvenement.FAIBLE;

        if (utilisateurId != null) {
            long recents = evenements.countByUtilisateurIdAndTypeAndDateHeureAfter(
                    utilisateurId, TypeEvenementSecurite.ECHEC_CONNEXION,
                    Instant.now().minus(FENETRE_ECHECS));

            if (recents >= SEUIL_ECHECS) {
                gravite = GraviteEvenement.HAUTE;
            }
        }

        evenements.save(new EvenementSecurite(utilisateurId,
                TypeEvenementSecurite.ECHEC_CONNEXION, gravite, adresseIp, raison));
    }
}
