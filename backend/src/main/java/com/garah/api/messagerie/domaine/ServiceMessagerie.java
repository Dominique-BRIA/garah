package com.garah.api.messagerie.domaine;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.messagerie.infra.BlocageMessageRepository;
import com.garah.api.messagerie.infra.FilInterneRepository;
import com.garah.api.messagerie.infra.MessageInterneRepository;
import com.garah.api.messagerie.infra.NomResponsableRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * La messagerie interne à l'équipe.
 *
 * <h2>🎯 Le blocage se vérifie À L'ENVOI, pas à l'ouverture du fil</h2>
 *
 * <p>Un blocage peut être posé alors que le fil existe déjà, et alors que
 * l'écran est ouvert. Ne le contrôler qu'à l'ouverture laisserait passer tout
 * ce qui est écrit ensuite — c'est-à-dire précisément ce que le blocage
 * voulait arrêter.</p>
 *
 * <h2>⚠️ Le blocage est ORIENTÉ</h2>
 *
 * <p>{@code bloqueur} cesse de recevoir de {@code bloque}. L'inverse reste
 * vrai : un directeur qui bloque un magasinier peut toujours lui écrire. Le
 * contraire ferait du blocage une rupture de la chaîne de commandement, alors
 * qu'on veut seulement se protéger d'une sollicitation.</p>
 *
 * <h2>⚠️ Ce module ne connaît AUCUN autre</h2>
 *
 * <p>Il ne manipule que des identifiants de responsables — jamais l'entité.
 * Savoir à qui l'on écrit n'est pas savoir qui c'est. Les noms sont empruntés
 * par une requête qui NOMME {@code Responsable} en HQL sans jamais l'importer,
 * le même procédé qu'entre {@code logistique} et {@code commerce}.</p>
 */
@Service
public class ServiceMessagerie {

    private final FilInterneRepository fils;
    private final MessageInterneRepository messages;
    private final BlocageMessageRepository blocages;
    private final NomResponsableRepository noms;
    private final ApplicationEventPublisher evenements;

    public ServiceMessagerie(FilInterneRepository fils,
                             MessageInterneRepository messages,
                             BlocageMessageRepository blocages,
                             NomResponsableRepository noms,
                             ApplicationEventPublisher evenements) {
        this.fils = fils;
        this.messages = messages;
        this.blocages = blocages;
        this.noms = noms;
        this.evenements = evenements;
    }

    // -------------------------------------------------------------------------
    // Écrire
    // -------------------------------------------------------------------------

    /**
     * Envoie un message, en ouvrant le fil s'il n'existe pas encore.
     *
     * <p>Il n'y a pas de geste « ouvrir un fil » séparé : un fil sans message
     * ne veut rien dire, et le créer d'abord obligerait à nettoyer les fils
     * vides de ceux qui se ravisent.</p>
     */
    @Transactional
    public VueMessageInterne envoyer(Long expediteurId, Long destinataireId, String contenu) {
        if (expediteurId.equals(destinataireId)) {
            throw new RegleMetierViolee("MESSAGE_A_SOI_MEME",
                    "On ne s'écrit pas à soi-même.");
        }

        // ⚠️ Le sens du test compte : le DESTINATAIRE m'a-t-il bloqué ? Tester
        //    l'inverse laisserait un bloqueur incapable d'écrire à celui qu'il
        //    a bloqué, ce qui n'est pas ce qu'on a voulu.
        if (blocages.existsByBloqueurIdAndBloqueId(destinataireId, expediteurId)) {
            throw new RegleMetierViolee("DESTINATAIRE_BLOQUE",
                    "Cette personne ne reçoit pas vos messages.");
        }

        FilInterne fil = fils
                .findByResponsableAAndResponsableB(
                        Math.min(expediteurId, destinataireId),
                        Math.max(expediteurId, destinataireId))
                .orElseGet(() -> fils.save(new FilInterne(expediteurId, destinataireId)));

        fil.toucher();
        MessageInterne message = messages.save(
                new MessageInterne(fil.getId(), expediteurId, contenu.strip()));

        // 🎯 Un ÉVÉNEMENT, et non un appel direct au temps réel ni aux
        //    notifications. La messagerie n'a pas à savoir qu'un WebSocket
        //    existe, ni que Firebase existe : le jour où l'un des deux tombe,
        //    le message doit quand même être enregistré.
        evenements.publishEvent(new MessageInterneEnvoye(
                message.getId(), fil.getId(), expediteurId, destinataireId,
                message.getContenu(), message.getDateEnvoi()));

        return VueMessageInterne.de(message);
    }

    // -------------------------------------------------------------------------
    // Lire
    // -------------------------------------------------------------------------

    /**
     * Mes fils, avec l'interlocuteur nommé et le nombre de non-lus.
     *
     * <p>Trois requêtes pour la page : les fils, les non-lus en un bloc, les
     * noms en un bloc. <b>Jamais une par ligne</b> — une pastille par fil est
     * exactement le genre de détail qui multiplie les requêtes en silence.</p>
     */
    @Transactional(readOnly = true)
    public List<VueFilInterne> mesFils(Long moi) {
        List<FilInterne> trouves = fils.miens(moi);
        if (trouves.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> nonLus = new HashMap<>();
        for (Object[] ligne : messages.nonLusPar(
                trouves.stream().map(FilInterne::getId).toList(), moi)) {
            nonLus.put((Long) ligne[0], (Long) ligne[1]);
        }

        Map<Long, String> nommes = nommer(
                trouves.stream().map(f -> f.interlocuteurDe(moi)).toList());

        return trouves.stream()
                .map(f -> {
                    Long autre = f.interlocuteurDe(moi);
                    return new VueFilInterne(
                            f.getId(),
                            autre,
                            // Un membre retiré laisse un fil sans nom. On
                            // l'affiche quand même : les messages ont bien été
                            // échangés, et les effacer réécrirait l'histoire.
                            nommes.getOrDefault(autre, "Membre retiré"),
                            nonLus.getOrDefault(f.getId(), 0L),
                            f.getDateDernier());
                })
                .toList();
    }

    /** Les collègues à qui l'on peut écrire. */
    @Transactional(readOnly = true)
    public List<VueCollegue> joignablesPar(Long moi) {
        return noms.joignablesPar(moi).stream()
                .map(l -> new VueCollegue((Long) l[0], nomComplet(l[1], l[2])))
                .toList();
    }

    @Transactional(readOnly = true)
    public long totalNonLus(Long responsableId) {
        return messages.totalNonLus(responsableId);
    }

    /**
     * Le fil complet — et il est marqué lu au passage.
     *
     * <p>⚠️ Marquer lu ICI, dans la lecture, et non par un appel séparé que le
     * frontend pourrait oublier. Une pastille qui ne redescend jamais fait
     * cesser de la regarder.</p>
     */
    @Transactional
    public List<VueMessageInterne> ouvrir(Long filId, Long responsableId) {
        FilInterne fil = fils.findById(filId)
                .orElseThrow(() -> RessourceIntrouvable.de("Fil", filId));

        // « Introuvable », jamais « interdit » : un 403 confirmerait que le fil
        // existe, et parcourir les identifiants dirait qui parle à qui.
        if (!fil.concerne(responsableId)) {
            throw RessourceIntrouvable.de("Fil", filId);
        }

        List<MessageInterne> lus = messages.findByFilIdOrderByDateEnvoiAsc(filId);
        lus.stream()
                .filter(m -> !m.getExpediteurId().equals(responsableId))
                .forEach(MessageInterne::lu);
        return lus.stream().map(VueMessageInterne::de).toList();
    }

    // -------------------------------------------------------------------------
    // Bloquer
    // -------------------------------------------------------------------------

    /**
     * Bloque un collègue.
     *
     * <p>Le droit de le faire est porté par la permission
     * {@code MESSAGE_INTERNE_BLOQUER}, contrôlée par la route. Il n'y a pas de
     * hiérarchie dans ce schéma, et on n'en invente pas une ici : « supérieur »
     * se traduit par « catégorie à qui l'Admin a donné cette permission ».</p>
     *
     * <p>Idempotent : bloquer deux fois ne fait rien de plus, et ne doit pas
     * échouer — sinon un double clic donne une erreur pour un état déjà
     * obtenu.</p>
     */
    @Transactional
    public void bloquer(Long bloqueurId, Long bloqueId, String motif) {
        if (bloqueurId.equals(bloqueId)) {
            throw new RegleMetierViolee("BLOCAGE_DE_SOI_MEME",
                    "On ne se bloque pas soi-même.");
        }
        if (blocages.existsByBloqueurIdAndBloqueId(bloqueurId, bloqueId)) {
            return;
        }
        blocages.save(new BlocageMessage(bloqueurId, bloqueId, motif));
    }

    @Transactional
    public void debloquer(Long bloqueurId, Long bloqueId) {
        blocages.findById(new BlocageMessage.Cle(bloqueurId, bloqueId))
                .ifPresent(blocages::delete);
    }

    @Transactional(readOnly = true)
    public List<VueBlocage> mesBlocages(Long bloqueurId) {
        List<BlocageMessage> trouves = blocages.findByBloqueurId(bloqueurId);
        if (trouves.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nommes = nommer(
                trouves.stream().map(BlocageMessage::getBloqueId).toList());

        return trouves.stream()
                .map(b -> new VueBlocage(
                        b.getBloqueId(),
                        nommes.getOrDefault(b.getBloqueId(), "Membre retiré"),
                        b.getMotif(),
                        b.getDateBlocage()))
                .toList();
    }

    // -------------------------------------------------------------------------

    private Map<Long, String> nommer(Collection<Long> ids) {
        Map<Long, String> resultat = new HashMap<>();
        for (Object[] ligne : noms.nomsPar(ids)) {
            resultat.put((Long) ligne[0], nomComplet(ligne[1], ligne[2]));
        }
        return resultat;
    }

    private static String nomComplet(Object prenom, Object nom) {
        String p = prenom == null ? "" : prenom.toString().trim();
        String n = nom == null ? "" : nom.toString().trim();
        return (p + " " + n).trim();
    }

    /** Vrai si {@code moi} ne peut plus écrire à {@code autre}. */
    @Transactional(readOnly = true)
    public boolean bloquePar(Long moi, Long autre) {
        return blocages.existsByBloqueurIdAndBloqueId(autre, moi);
    }
}
