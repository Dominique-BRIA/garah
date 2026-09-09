package com.garah.api.surveillance.domaine;

import com.garah.api.commun.audit.GesteClient.TypeGesteClient;
import com.garah.api.surveillance.infra.ActiviteClientRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Écrit et relit le parcours d'un client.
 *
 * <h2>🎯 Ce qui manquait</h2>
 *
 * <p>{@code activite_client} est déclarée depuis V12 et n'avait <b>ni entité
 * ni écriture</b>. {@code EcouteurAudit} écarte d'ailleurs explicitement les
 * acteurs clients depuis le début, en disant que « le parcours d'un client est
 * une autre question, qui a son propre journal ». Ce journal-là.</p>
 *
 * <h2>⚠️ {@code REQUIRES_NEW}, et le rattrapage vit CHEZ L'APPELANT</h2>
 *
 * <p>L'écriture ouvre sa <b>propre</b> transaction : un échec ici ne doit pas
 * marquer celle de la commande comme « à annuler ». C'est le même choix que
 * {@code ServiceAudit}.</p>
 *
 * <p>⚠️ Le {@code try/catch} ne peut PAS vivre dans cette classe. Rattraper
 * l'exception à l'intérieur de la méthode transactionnelle ne sert à rien :
 * la transaction est déjà marquée « à annuler », et Spring lève un
 * {@code UnexpectedRollback} au moment du commit — c'est-à-dire APRÈS le
 * {@code catch}. L'appel échouerait quand même, et pour une raison qu'on ne
 * relierait jamais au journal.</p>
 *
 * <p>C'est {@link EcouteurActiviteClient} qui rattrape, depuis l'extérieur de
 * la transaction. Un test le tient — il a d'ailleurs attrapé la première
 * version de ce service, qui faisait exactement l'erreur décrite ci-dessus.</p>
 */
@Service
public class ServiceActiviteClient {

    private final ActiviteClientRepository activites;

    public ServiceActiviteClient(ActiviteClientRepository activites) {
        this.activites = activites;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrer(Long clientId, TypeGesteClient type,
                            Map<String, String> contexte, String adresseIp) {
        activites.save(new ActiviteClient(clientId, type, contexte, adresseIp));
    }

    /**
     * Le parcours d'un client, du plus récent au plus ancien.
     *
     * <p>Réservé au back-office : c'est la lecture que la permission
     * {@code CLIENT_CONSULTER_HISTORIQUE} désigne depuis le référentiel
     * d'origine, et qui n'avait jusqu'ici <b>rien à garder</b>.</p>
     */
    @Transactional(readOnly = true)
    public Page<VueActiviteClient> parcoursDe(Long clientId, Pageable pagination) {
        return activites.findByClientIdOrderByDateHeureDescIdDesc(clientId, pagination)
                .map(VueActiviteClient::de);
    }
}
