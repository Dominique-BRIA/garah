package com.garah.api.serviceclient.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.domaine.NomClient;
import com.garah.api.iam.domaine.ServiceClient;
import com.garah.api.serviceclient.infra.AffectationConversationRepository;
import com.garah.api.serviceclient.infra.ConversationRepository;
import com.garah.api.serviceclient.infra.EvaluationConversationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Les conversations entre clients et responsables.
 *
 * <p>Le sujet technique de ce service est la <b>file d'attente partagée</b> :
 * plusieurs responsables voient la même liste de conversations en attente, et
 * le premier qui clique doit gagner — sans que les autres reçoivent une erreur
 * incompréhensible.</p>
 */
@Service
public class ServiceConversation {

    private final ConversationRepository conversations;
    private final AffectationConversationRepository affectations;
    private final EvaluationConversationRepository evaluations;

    /** Pour NOMMER le client dans les listes, jamais pour le modifier. */
    private final ServiceClient clients;

    /** ⚠️ `utilisateur` et non `responsable` : depuis V33, un administrateur
     *  peut prendre et clore une conversation. */
    private final com.garah.api.iam.infra.UtilisateurRepository utilisateurs;
    private final org.springframework.context.ApplicationEventPublisher evenements;

    public ServiceConversation(com.garah.api.iam.infra.UtilisateurRepository utilisateurs,
                               ConversationRepository conversations,
                               AffectationConversationRepository affectations,
                               EvaluationConversationRepository evaluations,
                               ServiceClient clients,
                               org.springframework.context.ApplicationEventPublisher evenements) {
        this.conversations = conversations;
        this.affectations = affectations;
        this.evaluations = evaluations;
        this.clients = clients;
        this.utilisateurs = utilisateurs;
        this.evenements = evenements;
    }

    @Transactional
    public Conversation ouvrir(Long clientId, String sujet, String premierMessage) {
        Conversation conversation = conversations.save(new Conversation(clientId, sujet));
        if (premierMessage != null && !premierMessage.isBlank()) {
            conversation.ajouterMessage(clientId, premierMessage);
        }

        // 🎯 Un signal d'ÉQUIPE : la conversation n'a encore aucun
        //    propriétaire, et c'est justement ce qu'il faut annoncer. La
        //    prévenir à personne en particulier la laisserait attendre.
        evenements.publishEvent(new EvenementsConversation.ConversationOuverte(
                conversation.getId(), clientId, sujet));

        return conversation;
    }

    /**
     * Un responsable prend une conversation en attente.
     *
     * <p><b>La technique diffère volontairement de celle du stock.</b></p>
     *
     * <pre>
     * Stock          verrou pessimiste     on a besoin de la valeur AVANT
     * Conversation   UPDATE conditionnel   on n'a besoin de rien
     * </pre>
     *
     * <p>Ici, une seule requête suffit :</p>
     *
     * <pre>UPDATE conversation SET … WHERE id = ? AND statut = 'WAITING'</pre>
     *
     * <p>Si elle modifie <b>une</b> ligne, on a gagné. Si elle en modifie
     * <b>zéro</b>, quelqu'un a été plus rapide. Pas de verrou, pas d'attente.</p>
     *
     * <p>Le message d'erreur compte : « un autre responsable a déjà pris cette
     * conversation » se comprend. « Erreur de mise à jour » ne se comprend
     * pas, et fait cliquer trois fois de plus.</p>
     */
    @Transactional
    public Conversation prendre(Long conversationId, Long prisPar) {
        // Sans cette garde, un responsable nul produirait une conversation
        // ASSIGNED sans responsable — que la contrainte
        // conversation_responsable_coherent refuse, avec un message
        // incompréhensible pour l'appelant.
        if (prisPar == null) {
            throw new RegleMetierViolee("RESPONSABLE_OBLIGATOIRE",
                    "Une conversation doit être prise par un responsable identifié.");
        }

        int lignes = conversations.prendre(conversationId, prisPar);

        if (lignes == 0) {
            conversations.findById(conversationId)
                    .orElseThrow(() -> RessourceIntrouvable.de("Conversation", conversationId));

            throw new ConflitEtat("CONVERSATION_DEJA_PRISE",
                    "Un autre responsable a déjà pris cette conversation.");
        }

        affectations.save(new AffectationConversation(conversationId, prisPar, null));
        return charger(conversationId);
    }

    /**
     * Un Admin retire une conversation à son responsable.
     *
     * <p>Le motif est <b>obligatoire</b> : retirer un dossier à quelqu'un est
     * une décision qui doit pouvoir s'expliquer, et elle sera relue lors de
     * l'évaluation du responsable (§20).</p>
     */
    @Transactional
    public Conversation reaffecter(Long conversationId, Long nouveauPrisPar,
                                   Long adminId, String motif) {
        if (motif == null || motif.isBlank()) {
            throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
                    "Un retrait de conversation doit être justifié.");
        }

        Conversation conversation = charger(conversationId);
        if (conversation.estFermee()) {
            throw new ConflitEtat("CONVERSATION_FERMEE",
                    "Une conversation fermée ne peut plus être réaffectée.");
        }

        // Clôturer l'affectation en cours AVANT d'en ouvrir une autre :
        // l'index unique partiel n'accepte qu'une affectation ouverte (I-30).
        affectations.findByConversationIdAndDateFinIsNull(conversationId)
                .ifPresent(a -> {
                    a.cloturer(motif);
                    affectations.saveAndFlush(a);
                });

        conversation.remettreEnAttente();
        conversations.saveAndFlush(conversation);

        if (nouveauPrisPar != null) {
            conversations.prendre(conversationId, nouveauPrisPar);
            affectations.save(new AffectationConversation(
                    conversationId, nouveauPrisPar, adminId));
        }

        return charger(conversationId);
    }

    @Transactional
    public Message repondre(Long conversationId, Long expediteurId, String contenu) {
        if (contenu == null || contenu.isBlank()) {
            throw new RegleMetierViolee("MESSAGE_VIDE", "Un message ne peut pas être vide.");
        }

        Conversation conversation = charger(conversationId);
        if (conversation.estFermee()) {
            throw new ConflitEtat("CONVERSATION_FERMEE",
                    "Cette conversation est fermée. Ouvrez-en une nouvelle.");
        }

        // 🎯 LA MAIN SE PREND TOUTE SEULE.
        //
        //    Répondre à un client, c'est prendre la conversation. L'exiger en
        //    deux gestes — « Prendre », puis « Répondre » — produisait des
        //    conversations traitées mais toujours affichées WAITING : un
        //    collègue les rouvrait pour découvrir qu'on y avait déjà répondu.
        //
        // ⚠️ Seulement si l'expéditeur N'EST PAS le client : sans cette garde,
        //    le client se verrait attribuer sa propre conversation dès son
        //    deuxième message, et la file d'attente se viderait toute seule.
        boolean estLeClient = expediteurId.equals(conversation.getClientId());
        boolean prise = !estLeClient && conversation.prendreSiLibre(expediteurId);

        if (prise) {
            // ⚠️ Journalisée SEULEMENT quand la prise a lieu : une ligne par
            //    réponse remplirait l'historique de doublons.
            // `affectePar` reste nul : personne ne l a affectee, elle a ete
            // prise. C est ce que fait deja `prendre()`.
            affectations.save(new AffectationConversation(
                    conversationId, expediteurId, null));
        }

        Message message = conversation.ajouterMessage(expediteurId, contenu);

        // ⚠️ VIDER AVANT DE PUBLIER. ajouterMessage() ne fait qu ajouter a
        //    la collection : l identifiant n est attribue qu au vidage
        //    Hibernate. Sans ce flush, la diffusion temps reel partirait avec
        //    un id nul, et l ecran d en face ne saurait pas reconnaitre un
        //    message qu il affiche deja.
        conversations.flush();

        // ⚠️ Le SENS se décide ici, où l'on sait qui est le client. Le deviner
        //    plus tard, dans l'écouteur, demanderait de recharger la
        //    conversation — et de se tromper le jour où un responsable est
        //    aussi client.
        boolean versLeClient = !estLeClient;

        evenements.publishEvent(new EvenementsConversation.MessageDansConversation(
                conversationId,
                conversation.getClientId(),
                conversation.getPrisPar(),
                expediteurId,
                extrait(contenu),
                versLeClient,
                VueMessage.de(message, conversationId)));

        return message;
    }

    /**
     * Les premiers mots du message, pour la bannière de notification.
     *
     * <p>⚠️ Un extrait, jamais le message entier : une notification affiche
     * deux lignes, et le reste est coupé par le système de toute façon. Y
     * mettre cinq mille caractères ne fait que gonfler l'envoi.</p>
     */
    private static String extrait(String contenu) {
        String propre = contenu.strip().replaceAll("\s+", " ");
        return propre.length() <= 120 ? propre : propre.substring(0, 117) + "…";
    }

    /**
     * Ferme, en retenant QUI ferme.
     *
     * <p>🎯 {@code date_cloture} disait quand. Rien ne disait qui — et c'est
     * la seule question qu'on pose en relisant une conversation close.</p>
     *
     * <p>⚠️ Reste idempotent : fermer deux fois n'est pas une erreur. Mais la
     * SECONDE fermeture ne réécrit pas l'auteur : celui qui a fermé est celui
     * qui a fermé le premier.</p>
     */
    @Transactional
    public Conversation fermer(Long conversationId, Long parQui) {
        Conversation conversation = charger(conversationId);
        if (conversation.estFermee()) {
            return conversation;
        }

        affectations.findByConversationIdAndDateFinIsNull(conversationId)
                .ifPresent(a -> a.cloturer("Conversation close"));

        conversation.fermer(parQui);
        return conversation;
    }

    /**
     * Le client évalue le service reçu.
     *
     * <p>Deux règles, et la première surprend : on ne peut évaluer qu'une
     * conversation <b>fermée</b>. Évaluer un échange en cours fausserait la
     * mesure de satisfaction de la §20 — le responsable n'a pas fini son
     * travail.</p>
     */
    @Transactional
    public EvaluationConversation evaluer(Long conversationId, int note, String commentaire) {
        Conversation conversation = charger(conversationId);

        if (!conversation.estFermee()) {
            throw new ConflitEtat("CONVERSATION_OUVERTE",
                    "Une conversation ne peut être évaluée qu'une fois close.");
        }
        if (note < 1 || note > 5) {
            throw new RegleMetierViolee("NOTE_INVALIDE", "La note doit être comprise entre 1 et 5.");
        }
        if (evaluations.findByConversationId(conversationId).isPresent()) {
            throw new ConflitEtat("DEJA_EVALUEE", "Cette conversation a déjà été évaluée.");
        }

        return evaluations.save(new EvaluationConversation(conversationId, note, commentaire));
    }

    /**
     * Mes conversations.
     *
     * <h2>🎯 Le client aussi a besoin d'une liste</h2>
     *
     * <p>Toutes les listes de ce service servaient le back-office. Le client,
     * lui, ouvrait une conversation et n'avait plus aucun moyen d'y revenir :
     * il fallait garder l'onglet ouvert. Une négociation qui dure deux jours
     * était donc perdue au premier rechargement.</p>
     *
     * <p>⚠️ Sans les messages ({@code resume}) : {@code getMessages()} est
     * paresseux, et une page de vingt conversations ferait vingt requêtes de
     * plus. Le fil complet se demande conversation par conversation, sur
     * celle qu'on ouvre.</p>
     */
    @Transactional(readOnly = true)
    public Page<VueConversation> miennes(Long clientId, Pageable pagination) {
        return conversations.findByClientIdOrderByDateCreationDesc(clientId, pagination)
                .map(VueConversation::resume);
    }

    /**
     * Une conversation, telle qu'elle est en base.
     *
     * <p>⚠️ Rendue au DOMAINE, pas en vue : c'est ce dont les tests ont besoin
     * pour éprouver un statut ou un auteur de clôture. Les écrans, eux,
     * passent par {@code vue()}.</p>
     */
    @Transactional(readOnly = true)
    public Conversation parId(Long conversationId) {
        return charger(conversationId);
    }

    @Transactional(readOnly = true)
    public List<Conversation> fileDAttente() {
        return conversations.findByStatutOrderByDateCreationAsc(StatutConversation.WAITING);
    }

    /** Combien de conversations attendent qu on les prenne, sans les charger. */
    @Transactional(readOnly = true)
    public long nombreEnFileDAttente() {
        return conversations.countByStatut(StatutConversation.WAITING);
    }

    /**
     * La liste du back-office, clients <b>nommés</b> et messages comptés.
     *
     * <p>Trois requêtes en tout, quelle que soit la taille de la page : les
     * conversations, leurs clients d'un coup, les totaux de leurs messages
     * d'un coup.</p>
     *
     * <p>🎯 Le chiffre qui compte est celui des <b>non lus</b>, pas le total.
     * La seule question que se pose un agent en ouvrant cette liste est
     * « laquelle attend ma réponse ? », et un total de messages n'y répond
     * pas.</p>
     *
     * @param prisPar non nul pour ne voir que ses propres dossiers.
     *                      Sans ce filtre, un agent parcourrait les
     *                      conversations de toute l'équipe pour retrouver les
     *                      siennes.
     */
    @Transactional(readOnly = true)
    public Page<ResumeConversation> administration(StatutConversation statut, Long prisPar,
                                                   Pageable pagination) {
        Page<Conversation> page = conversations.administration(statut, prisPar, pagination);

        if (page.isEmpty()) {
            // `IN ()` sur une collection vide est refusé par certains
            // dialectes : on ne pose pas la question quand il n'y a rien à
            // demander.
            return page.map(c -> ResumeConversation.de(c, null, 0, 0, null));
        }

        Map<Long, NomClient> noms = clients.nomsPar(page.map(Conversation::getClientId).toList());

        Map<Long, Object[]> totaux = conversations.totauxPar(page.map(Conversation::getId).toList())
                .stream()
                .collect(Collectors.toMap(ligne -> (Long) ligne[0], ligne -> ligne));

        // ⚠️ EN UN APPEL, pour toute la page. Résoudre un nom par ligne, c'est
        //    vingt requêtes sur vingt conversations — invisible ici, coûteux
        //    en production.
        Map<Long, String> internes = nomsInternes(page.stream()
                .flatMap(c -> Stream.of(c.getPrisPar(), c.getClosPar()))
                .filter(Objects::nonNull)
                .toList());

        return page.map(c -> {
            Object[] total = totaux.get(c.getId());
            return ResumeConversation.de(c, noms.get(c.getClientId()),
                    nomDe(internes, c.getPrisPar()), nomDe(internes, c.getClosPar()),
                    total == null ? 0 : ((Number) total[1]).longValue(),
                    total == null ? 0 : ((Number) total[2]).longValue(),
                    total == null ? null : (Instant) total[3]);
        });
    }

    /**
     * ⚠️ Une conversation en attente n'a été prise par PERSONNE : son
     * identifiant est nul. Et {@code Map.of()} refuse une clé nulle même en
     * LECTURE — elle lève, là où {@code HashMap} rendrait {@code null}. Trois
     * tests l'ont attrapé ; en production, c'était la liste des conversations
     * qui tombait dès qu'une seule attendait.
     */
    private static String nomDe(Map<Long, String> noms, Long id) {
        return id == null ? null : noms.get(id);
    }

    /**
     * Les noms des comptes internes cités par une conversation.
     *
     * <p>⚠️ Un identifiant absent de la carte rend {@code null}, et c'est
     * voulu : un compte supprimé laisse une conversation dont on sait qu'elle
     * a été prise, sans savoir par qui. Mieux vaut le dire que d'inventer.</p>
     */
    @Transactional(readOnly = true)
    public Map<Long, String> nomsInternes(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return utilisateurs.nomsPar(ids).stream().collect(Collectors.toMap(
                l -> (Long) l[0],
                l -> {
                    String prenom = (String) l[1];
                    String nom = (String) l[2];
                    return prenom == null || prenom.isBlank() ? nom : prenom + " " + nom;
                },
                (unNom, autre) -> unNom));
    }

    /** Les messages de clients jamais ouverts, pour la pastille du menu. */
    @Transactional(readOnly = true)
    public long totalNonLus() {
        return conversations.totalNonLus();
    }

    @Transactional(readOnly = true)
    public List<AffectationConversation> historique(Long conversationId) {
        return affectations.findByConversationIdOrderByDateDebutAsc(conversationId);
    }

    private Conversation charger(Long conversationId) {
        return conversations.findById(conversationId)
                .orElseThrow(() -> RessourceIntrouvable.de("Conversation", conversationId));
    }

    /**
     * Une conversation avec tous ses messages, prête à être sérialisée.
     *
     * <p>La conversion en DTO a lieu ICI, dans la transaction. {@code getMessages()}
     * est une collection paresseuse et {@code open-in-view} vaut {@code false} :
     * la convertir dans le contrôleur lèverait un {@code LazyInitializationException}
     * — à l'exécution seulement, jamais à la compilation.</p>
     */
    @Transactional(readOnly = true)
    public VueConversation vue(Long conversationId) {
        return VueConversation.complete(conversations.findById(conversationId)
                .orElseThrow(() -> RessourceIntrouvable.de("Conversation", conversationId)));
    }

    /**
     * Évalue, et renvoie un DTO plutôt que l'entité.
     *
     * <p>Surcouche mince sur {@link #evaluer} : la couche web n'a pas le droit
     * de toucher une entité JPA, et ArchUnit le vérifie à chaque build.</p>
     */
    @Transactional
    public VueEvaluation evaluerEtResumer(Long conversationId, int note, String commentaire) {
        return VueEvaluation.de(evaluer(conversationId, note, commentaire));
    }

    /**
     * Vérifie qu'un appelant a le droit de toucher à cette conversation.
     *
     * <h2>⚠️ Ce contrôle manquait, et c'était une fuite de données</h2>
     *
     * <p>Les routes de conversation n'exigent aucune permission : un client
     * n'en a aucune, son accès repose sur la <b>propriété</b> de ses données
     * (chapitre 08). Mais tant que personne ne vérifiait cette propriété,
     * « aucune permission exigée » voulait dire « ouvert à tout compte
     * connecté ».</p>
     *
     * <p>N'importe quel client pouvait donc, en changeant un identifiant :
     * lire la conversation d'un autre, y écrire, consulter ses prix négociés,
     * et <b>accepter ou refuser ses propositions</b>.</p>
     *
     * <p><b>On répond « introuvable », jamais « interdit ».</b> Un 403
     * confirmerait que la conversation existe — et parcourir les identifiants
     * suffirait à mesurer l'activité du service client.</p>
     *
     * @param estClient un client ne voit que les siennes ; un responsable, dont
     *                  le métier est justement de traiter celles des autres,
     *                  passe. Ses droits sont contrôlés par les
     *                  {@code @PreAuthorize} des routes qui lui sont réservées.
     */
    @Transactional(readOnly = true)
    public void exigerAcces(Long conversationId, Long utilisateurId, boolean estClient) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> RessourceIntrouvable.de("Conversation", conversationId));

        if (estClient && !conversation.getClientId().equals(utilisateurId)) {
            throw RessourceIntrouvable.de("Conversation", conversationId);
        }
    }

}
