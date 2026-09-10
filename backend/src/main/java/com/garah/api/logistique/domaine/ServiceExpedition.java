package com.garah.api.logistique.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.logistique.infra.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * L'acheminement des marchandises.
 *
 * <p>Le principe directeur de tout le module tient en une phrase :</p>
 *
 * <blockquote>
 * La vérité est la suite des <b>événements</b>. Le statut n'en est qu'une
 * <b>projection</b>, mise à jour dans la même transaction, et recalculable à
 * tout moment.
 * </blockquote>
 */
@Service
public class ServiceExpedition {

    private static final SecureRandom ALEA = new SecureRandom();
    private static final String ALPHABET = "ACDEFGHJKLMNPQRSTUVWXYZ2345679";

    /** Ce qui peut encore partir : payé, et pas encore tout expédié. */
    private static final java.util.Set<String> STATUTS_EXPEDIABLES =
            java.util.Set.of("PAYEE", "EN_PREPARATION", "PRETE", "EXPEDIEE");

    private final ExpeditionRepository expeditions;
    private final ColisRepository colis;
    private final EvenementExpeditionRepository evenements;
    private final RetraitMarchandiseRepository retraits;
    private final LieuRepository lieux;
    private final ApplicationEventPublisher journal;

    public ServiceExpedition(ExpeditionRepository expeditions, ColisRepository colis,
                             EvenementExpeditionRepository evenements,
                             RetraitMarchandiseRepository retraits, LieuRepository lieux,
                             ApplicationEventPublisher journal) {
        this.expeditions = expeditions;
        this.colis = colis;
        this.evenements = evenements;
        this.retraits = retraits;
        this.lieux = lieux;
        this.journal = journal;
    }

    /**
     * Crée l'expédition d'une commande.
     *
     * <p>🎯 <b>La destination se déduit de la commande.</b> Le client a choisi
     * son point de récupération en commandant, et les frais d'acheminement de
     * <b>ce</b> point ont été figés sur la commande (D-10). Redemander la
     * destination à l'opérateur lui donnerait le moyen d'envoyer la
     * marchandise dans une autre ville que celle payée — et rien, ensuite, ne
     * rapprocherait les deux.</p>
     *
     * <p>Ce qui se saisit, c'est le <b>départ</b> : la même commande peut
     * partir de Douala ou d'un stock déjà consolidé à Bertoua, et ça, seul
     * l'opérateur le sait.</p>
     */
    @Transactional
    public Expedition creer(Long commandeId, Long lieuDepartId, Long itineraireId) {
        Long destination = expeditions.destinationDe(commandeId)
                .orElseThrow(() -> new RegleMetierViolee("COMMANDE_INTROUVABLE",
                        "Cette commande n'existe pas : impossible de savoir où livrer."));
        return creer(commandeId, lieuDepartId, destination, itineraireId);
    }

    /**
     * Le même geste, avec une destination imposée.
     *
     * <p>Réservé aux tests et aux reprises de données. <b>Aucune route web ne
     * l'expose</b> : côté back-office, la destination se déduit toujours de la
     * commande.</p>
     */
    @Transactional
    public Expedition creer(Long commandeId, Long lieuDepartId, Long pointRecuperationId,
                            Long itineraireId) {
        // ⚠️ LA REGLE VIT ICI, PAS DANS L'ECRAN.
        //
        //    Le back-office cachait deja le bouton sur une commande impayee.
        //    Mais le serveur, lui, ne verifiait rien : tout autre chemin que
        //    cet ecran pouvait preparer l'envoi d'une marchandise jamais
        //    payee. Une protection qui ne tient que dans l'interface n'en est
        //    pas une.
        String statut = expeditions.statutDeCommande(commandeId)
                .orElseThrow(() -> new RegleMetierViolee("COMMANDE_INTROUVABLE",
                        "Cette commande n'existe pas : impossible de savoir où livrer."));
        if (!STATUTS_EXPEDIABLES.contains(statut)) {
            throw new ConflitEtat("COMMANDE_NON_EXPEDIABLE",
                    "EN_ATTENTE_PAIEMENT".equals(statut)
                            ? "Cette commande n'est pas encore payée : rien ne part avant le paiement."
                            : "Cette commande est close : il n'y a plus rien à envoyer.");
        }

        Lieu depart = charger(lieuDepartId);
        Lieu arrivee = charger(pointRecuperationId);

        if (arrivee.getType() != TypeLieu.POINT_RECUPERATION) {
            throw new RegleMetierViolee("LIEU_INVALIDE",
                    "La destination doit être un point de récupération.");
        }
        if (depart.getId().equals(arrivee.getId())) {
            throw new RegleMetierViolee("TRAJET_INVALIDE",
                    "Le départ et la destination ne peuvent pas être le même lieu.");
        }

        Expedition expedition = new Expedition(genererNumero(), commandeId,
                lieuDepartId, pointRecuperationId);
        expedition.setItineraireId(itineraireId);   // facultatif (A9)

        Expedition creee = expeditions.save(expedition);
        journal.publishEvent(new EvenementsExpedition.LogistiqueAvancee(commandeId));
        return creee;
    }

    /**
     * Ajoute un colis, avec un numéro de suivi ENGENDRÉ.
     *
     * <h2>🎯 Ce qui est engendré n'est jamais saisi</h2>
     *
     * <p>Le numéro était facultatif ici, obligatoire dans la route, et l'écran
     * disait « laisser vide pour l'engendrer » : laissé vide, il était refusé
     * par la route avant d'atteindre ce service. Il n'y a plus rien à saisir.</p>
     *
     * <p>C'est le numéro de GARAH : le guichet public le lit, le client le
     * reçoit dans ses discussions. Saisi à la main, il pourrait contenir un O
     * pour un 0, ou déjà appartenir à un autre colis.</p>
     */
    @Transactional
    public Colis ajouterColis(Long expeditionId) {
        Expedition expedition = chargerExpedition(expeditionId);

        if (expedition.getStatut() != StatutExpedition.CREEE
                && expedition.getStatut() != StatutExpedition.PREPAREE) {
            throw new ConflitEtat("EXPEDITION_PARTIE",
                    "On ne peut plus ajouter de colis à une expédition déjà partie.");
        }

        return expedition.ajouterColis(genererNumeroSuivi());
    }

    /**
     * Range des articles dans un colis.
     *
     * <p>⚠️ Le trigger {@code ligne_colis_quantite_trigger} (I-35) refuse de
     * mettre en colis plus que la quantité commandée. Il est <b>invisible</b>
     * depuis ce code : si l'insertion échoue, l'erreur vient de la base.
     * C'est pour ça qu'il est documenté au chapitre 04 et dans la migration —
     * un trigger non documenté est un piège qu'on se tend à soi-même.</p>
     */
    @Transactional
    public LigneColis remplir(Long colisId, Long ligneCommandeId, int quantite) {
        if (quantite < 1) {
            throw new RegleMetierViolee("QUANTITE_INVALIDE",
                    "Un colis contient au moins une unité.");
        }
        Colis paquet = chargerColis(colisId);
        LigneColis ligne = paquet.ajouterLigne(ligneCommandeId, quantite);
        journal.publishEvent(new EvenementsExpedition.LogistiqueAvancee(
                paquet.getExpedition().getCommandeId()));
        return ligne;
    }

    /**
     * Enregistre un fait du parcours, et met à jour la projection.
     *
     * <p>Les deux écritures — l'événement <b>et</b> la projection — sont dans
     * la même transaction. Si elles divergeaient, le statut mentirait sans
     * que rien ne le signale.</p>
     *
     * <p>Remarque ce qui n'est <b>pas</b> vérifié : que le lieu appartienne à
     * l'itinéraire prévu. Une route coupée, un déroutement, ça arrive — et
     * une contrainte qui empêche d'enregistrer la réalité pousse l'opérateur
     * à saisir n'importe quoi d'autre.</p>
     */
    @Transactional
    public EvenementExpedition enregistrer(Long colisId, Long lieuId, Long responsableId,
                                           TypeEvenement type, String observation) {
        Colis paquet = chargerColis(colisId);
        Lieu lieu = charger(lieuId);

        // ⚠️ Se lit AVANT `appliquer`, qui écrase le statut.
        //
        //    Un colis n'est CREE que jusqu'à son premier départ. C'est donc la
        //    seule façon de distinguer « il part » de « il repart » — un colis
        //    débloqué, ou relancé depuis une étape intermédiaire, ne doit pas
        //    annoncer une seconde fois au client que sa commande est partie.
        boolean premierDepart = type == TypeEvenement.DEPART
                && paquet.getStatut() == StatutColis.CREE;

        // ⚠️ UN COLIS VIDE NE PART PAS.
        //
        //    Son depart annoncait au client « votre commande est partie », avec
        //    un numero de suivi — pour un colis qui ne contenait rien. Pendant
        //    ce temps la commande, qui ignore les colis vides, restait « en
        //    preparation » : deux messages contradictoires le meme jour.
        if (type == TypeEvenement.DEPART && paquet.getLignes().isEmpty()) {
            throw new RegleMetierViolee("COLIS_VIDE",
                    "Ce colis est vide : rangez-y d'abord les articles de la commande.");
        }

        if (paquet.getStatut() == StatutColis.REMIS) {
            throw new ConflitEtat("COLIS_DEJA_REMIS",
                    "Ce colis a déjà été remis au client.");
        }
        if (type == TypeEvenement.ANOMALIE
                && (observation == null || observation.isBlank())) {
            // Une anomalie sans description bloque le colis sans dire pourquoi.
            throw new RegleMetierViolee("OBSERVATION_OBLIGATOIRE",
                    "Une anomalie doit être décrite.");
        }

        EvenementExpedition evenement = evenements.save(new EvenementExpedition(
                colisId, lieuId, responsableId, type, observation));

        boolean auPointDeRecuperation = lieu.getType() == TypeLieu.POINT_RECUPERATION;
        paquet.appliquer(type, auPointDeRecuperation);
        paquet.getExpedition().projeterDepuisLesColis();

        if (type == TypeEvenement.DEPART
                && paquet.getExpedition().getDateExpedition() == null) {
            paquet.getExpedition().marquerExpediee();
        }

        if (premierDepart) {
            prevenirDuDepart(paquet);
        }

        // 🎯 LE CODE DE RETRAIT NAIT A L'ARRIVEE — sans que personne y pense.
        //
        //    Il fallait qu'un agent clique « Préparer le retrait » une fois la
        //    marchandise arrivée. Tant qu'il n'y pensait pas, le client
        //    n'avait ni code ni nouvelle : son colis l'attendait au comptoir,
        //    et il l'ignorait. Venu quand même, on ne pouvait rien lui remettre.
        //
        // ⚠️ Dans la MEME transaction que l'arrivée : « tout est au comptoir »
        //    et « le client peut venir le chercher » sont vrais ensemble.
        Expedition envoi = paquet.getExpedition();
        if (envoi.getStatut() == StatutExpedition.DISPONIBLE
                && retraits.findByExpeditionId(envoi.getId()).isEmpty()) {
            preparerRetrait(envoi.getId());
        }

        journal.publishEvent(new EvenementsExpedition.LogistiqueAvancee(envoi.getCommandeId()));

        return evenement;
    }

    /**
     * Recalcule le statut d'un colis depuis son journal.
     *
     * <p>C'est le <b>test de la projection</b>, le pendant de la
     * réconciliation du stock (chapitre 11 §10). Il ne sert à rien le jour où
     * on l'écrit ; il attrapera un bug dans deux ans, quand quelqu'un ajoutera
     * un type d'événement en oubliant la projection.</p>
     */
    @Transactional(readOnly = true)
    public boolean projectionCoherente(Long colisId) {
        Colis paquet = chargerColis(colisId);

        return evenements.findFirstByColisIdOrderByDateHeureDescIdDesc(colisId)
                .map(dernier -> {
                    Lieu lieu = charger(dernier.getLieuId());
                    StatutColis attendu = Colis.projeter(dernier.getType(),
                            lieu.getType() == TypeLieu.POINT_RECUPERATION);
                    // CONTROLE ne change rien : le statut précédent reste valide.
                    return attendu == null || attendu == paquet.getStatut();
                })
                .orElse(paquet.getStatut() == StatutColis.CREE);
    }

    // -------------------------------------------------------------------------
    // Retrait
    // -------------------------------------------------------------------------

    /**
     * Prépare le retrait et génère le code que le client présentera.
     *
     * <p>Le code exclut les caractères ambigus ({@code I}, {@code O},
     * {@code 0}, {@code 1}, {@code 8}, {@code B}) : il sera lu à voix haute,
     * recopié à la main, parfois épelé au téléphone. Un « 0 » confondu avec
     * un « O » fait revenir le client le lendemain.</p>
     *
     * <p>🎯 <b>Le destinataire ne se saisit pas, il se déduit.</b> L'agent qui
     * prépare un retrait a sous les yeux une expédition, pas un identifiant de
     * client. Le lui faire taper n'ajouterait aucune information — mais une
     * faute de frappe préparerait le retrait de quelqu'un d'autre, et le code
     * partirait au mauvais destinataire.</p>
     */
    @Transactional
    public RetraitMarchandise preparerRetrait(Long expeditionId) {
        Long clientId = expeditions.clientDe(expeditionId)
                .orElseThrow(() -> new RegleMetierViolee("EXPEDITION_SANS_DESTINATAIRE",
                        "Cette expédition n'est rattachée à aucune commande : "
                        + "impossible de savoir à qui remettre la marchandise."));
        return preparerRetrait(expeditionId, clientId);
    }

    /**
     * Le même geste, avec un destinataire imposé.
     *
     * <p>Réservé aux tests et aux reprises de données. <b>Aucune route web ne
     * l'expose</b> : côté back-office, le destinataire se déduit toujours de la
     * commande.</p>
     */
    @Transactional
    public RetraitMarchandise preparerRetrait(Long expeditionId, Long clientId) {
        Expedition expedition = chargerExpedition(expeditionId);

        // ⚠️ IDEMPOTENT : le retrait se prepare desormais TOUT SEUL a
        //    l'arrivee. Le bouton du back-office reste, pour les envois
        //    arrives avant ce changement — et le cliquer apres coup doit
        //    rendre le retrait existant, pas une erreur.
        var existant = retraits.findByExpeditionId(expeditionId);
        if (existant.isPresent()) {
            return existant.get();
        }
        if (expedition.getStatut() != StatutExpedition.DISPONIBLE) {
            throw new ConflitEtat("EXPEDITION_NON_ARRIVEE",
                    "La marchandise n'est pas encore disponible au point de récupération.");
        }

        RetraitMarchandise retrait =
                retraits.save(new RetraitMarchandise(expeditionId, clientId, genererCode()));

        // Le NOM du point, pas son identifiant : « point 12 » n'a jamais
        // conduit personne quelque part.
        String point = lieux.findById(expedition.getPointRecuperationId())
                .map(Lieu::getNom)
                .orElse(null);
        journal.publishEvent(new EvenementsExpedition.MarchandiseDisponible(
                clientId, expedition.getCommandeId(), point));

        return retrait;
    }

    /**
     * Annoncer le départ au client, avec son numéro de suivi.
     *
     * <p>⚠️ Le propriétaire de la commande peut manquer — une expédition
     * rattachée à une commande effacée, par exemple. On se tait alors, plutôt
     * que d'empêcher un colis de partir : la notification est un service rendu
     * au client, jamais une condition de l'acheminement.</p>
     */
    private void prevenirDuDepart(Colis paquet) {
        Long commandeId = paquet.getExpedition().getCommandeId();
        expeditions.proprietaireDe(commandeId).ifPresent(clientId ->
                journal.publishEvent(new EvenementsExpedition.ColisParti(
                        clientId, commandeId, paquet.getNumeroSuivi())));
    }

    /**
     * Ce que le code désigne, sans rien remettre encore.
     *
     * <p>🎯 <b>Voir avant de remettre.</b> Confirmer un retrait sans savoir
     * quels colis il concerne serait un geste aveugle : le code serait validé,
     * mais rien ne garantirait que la bonne marchandise est sortie du
     * rayonnage.</p>
     *
     * <p>La méthode est en lecture seule et ne change aucun statut. Elle peut
     * donc être appelée autant de fois que l'agent se trompe de touche — un
     * code mal tapé ne consomme rien.</p>
     */
    @Transactional(readOnly = true)
    public VueComptoir auComptoir(String codeRetrait) {
        RetraitMarchandise retrait = retraits.findByCodeRetrait(codeRetrait)
                .orElseThrow(() -> new RegleMetierViolee("CODE_INVALIDE",
                        "Aucun retrait ne correspond à ce code."));

        // chargerAvecColis : la vue liste les colis, et getColis() est
        // paresseux — hors transaction, la liste arriverait vide.
        Expedition expedition = expeditions.chargerAvecColis(retrait.getExpeditionId())
                .orElseThrow(() -> new RegleMetierViolee("EXPEDITION_INTROUVABLE",
                        "L'expédition de ce retrait est introuvable."));

        return VueComptoir.de(retrait, expedition);
    }

    /**
     * Confirme la remise contre le code présenté par le client.
     *
     * <p>Le code est cherché <b>par lui-même</b>, pas par l'identifiant de
     * l'expédition : c'est ce qui en fait une preuve. Chercher l'expédition
     * puis comparer permettrait à un opérateur pressé de sauter la
     * vérification.</p>
     */
    @Transactional
    public RetraitMarchandise confirmerRetrait(String codeRetrait, Long responsableId) {
        RetraitMarchandise retrait = retraits.findByCodeRetrait(codeRetrait)
                .orElseThrow(() -> new RegleMetierViolee("CODE_INVALIDE",
                        "Aucun retrait ne correspond à ce code."));

        if (retrait.estConfirme()) {
            throw new ConflitEtat("RETRAIT_DEJA_CONFIRME",
                    "Cette marchandise a déjà été remise le " + retrait.getDateRetrait() + ".");
        }

        Expedition expedition = chargerExpedition(retrait.getExpeditionId());

        // Un événement REMISE par colis : la remise est un fait du parcours,
        // pas seulement un changement de statut.
        expedition.getColis().forEach(paquet ->
                enregistrer(paquet.getId(), expedition.getPointRecuperationId(),
                        responsableId, TypeEvenement.REMISE, null));

        retrait.confirmer(responsableId);
        return retrait;
    }

    /**
     * Le retrait de MA commande — code compris.
     *
     * <h2>🎯 La seule route de retrait ouverte au client</h2>
     *
     * <p>Toutes les autres exigent une autorité du back-office. Sans
     * celle-ci, le client ne pouvait <b>pas</b> connaître le code qui lui est
     * pourtant destiné : il fallait le lui lire au téléphone, et le seul
     * moyen de prouver une remise circulait donc à la voix.</p>
     *
     * <h2>⚠️ Une liste, et non un retrait</h2>
     *
     * <p>Une commande passée chez deux marchands part rarement d'un seul
     * entrepôt le même jour : elle a alors <b>deux</b> expéditions, donc deux
     * codes, à retirer séparément. Rendre le premier trouvé enverrait le
     * client chercher la moitié de sa commande en croyant tout emporter.</p>
     *
     * <h2>⚠️ « Introuvable », jamais « interdit »</h2>
     *
     * <p>Même raison que {@code detailPourClient} : un 403 confirmerait que la
     * commande existe, et parcourir les identifiants suffirait à reconstituer
     * le volume d'affaires de la plateforme. Une commande qui n'est pas la
     * sienne se comporte donc exactement comme une commande qui n'existe pas.</p>
     *
     * <p>Le filtre porte sur le <b>propriétaire du retrait</b>, pas sur celui
     * de la commande : c'est lui qui désigne la personne autorisée à repartir
     * avec la marchandise, et c'est la seule vérification qui protège le code.</p>
     */
    @Transactional(readOnly = true)
    public List<MonRetrait> mesRetraits(Long commandeId, Long clientId) {
        // ⚠️ La propriété se vérifie AVANT de regarder s'il existe un retrait,
        //    et non en filtrant la liste obtenue.
        //
        //    Filtrer aurait confondu deux situations très différentes : une
        //    commande légitime dont le retrait n'est pas encore préparé, et la
        //    commande de quelqu'un d'autre. Les deux rendent une liste vide.
        Long proprietaire = expeditions.proprietaireDe(commandeId)
                .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));

        if (!proprietaire.equals(clientId)) {
            throw RessourceIntrouvable.de("Commande", commandeId);
        }

        // Vide tant que RIEN N'EST PARTI. Ce n'est pas une erreur : c'est
        // l'état normal d'une commande qu'on vient de payer, et l'écran doit
        // savoir le dire.
        //
        // ⚠️ Ce qui suit partait auparavant des RETRAITS, préparés bien plus
        //    tard par un agent du comptoir. Un envoi en cours de route ne
        //    remontait donc pas, et le client n'avait aucun numéro de suivi
        //    tant que son colis n'était pas arrivé. On part maintenant des
        //    EXPÉDITIONS : le retrait vient s'y ajouter quand il existe.
        return expeditions.findByCommandeId(commandeId).stream()
                .map(e -> MonRetrait.de(e,
                        retraits.findByExpeditionId(e.getId()).stream().findFirst().orElse(null)))
                .toList();
    }

    @Transactional
    public RetraitMarchandise refuserRetrait(String codeRetrait, String motif) {
        RetraitMarchandise retrait = retraits.findByCodeRetrait(codeRetrait)
                .orElseThrow(() -> new RegleMetierViolee("CODE_INVALIDE",
                        "Aucun retrait ne correspond à ce code."));

        if (retrait.estConfirme()) {
            throw new ConflitEtat("RETRAIT_DEJA_CONFIRME",
                    "Cette marchandise a déjà été remise.");
        }
        retrait.refuser();
        return retrait;
    }

    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EvenementExpedition> parcours(Long colisId) {
        return evenements.findByColisIdOrderByDateHeureAsc(colisId);
    }

    @Transactional(readOnly = true)
    public List<EvenementExpedition> suivrePar(String numeroSuivi) {
        Colis paquet = colis.findByNumeroSuivi(numeroSuivi)
                .orElseThrow(() -> RessourceIntrouvable.de("Colis", numeroSuivi));
        return parcours(paquet.getId());
    }

    /**
     * Le suivi public : le trajet, avec des noms de lieux plutôt que des
     * numéros.
     *
     * <p>Les lieux sont chargés en <b>une seule requête</b> pour tout le
     * trajet, pas un appel par étape. Un colis qui a traversé six points de
     * transit ferait sinon sept requêtes pour afficher sept lignes — et cette
     * route est publique, donc appelable en rafale.</p>
     */
    @Transactional(readOnly = true)
    public VueSuivi suiviPublic(String numeroSuivi) {
        Colis paquet = colis.findByNumeroSuivi(numeroSuivi)
                .orElseThrow(() -> RessourceIntrouvable.de("Colis", numeroSuivi));

        List<EvenementExpedition> trajet = parcours(paquet.getId());

        Map<Long, Lieu> parId = lieux.findAllById(
                        trajet.stream().map(EvenementExpedition::getLieuId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Lieu::getId, l -> l));

        List<VueSuivi.Etape> etapes = trajet.stream()
                .map(e -> {
                    Lieu lieu = parId.get(e.getLieuId());
                    return new VueSuivi.Etape(
                            e.getType().name(),
                            lieu == null ? null : lieu.getNom(),
                            lieu == null ? null : lieu.getVille(),
                            e.getObservation(),
                            e.getDateHeure());
                })
                .toList();

        return new VueSuivi(paquet.getNumeroSuivi(), paquet.getStatut().name(), etapes);
    }

    private String genererNumero() {
        return "EXP-%d-%06d".formatted(Year.now().getValue(), expeditions.prochainNumero());
    }

    private String genererNumeroSuivi() {
        return "GRH" + Instant.now().toEpochMilli() + tirer(4);
    }

    private String genererCode() {
        return tirer(4) + "-" + tirer(4);
    }

    private String tirer(int longueur) {
        StringBuilder code = new StringBuilder(longueur);
        for (int i = 0; i < longueur; i++) {
            code.append(ALPHABET.charAt(ALEA.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private Expedition chargerExpedition(Long id) {
        return expeditions.chargerAvecColis(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Expédition", id));
    }

    private Colis chargerColis(Long id) {
        return colis.findById(id).orElseThrow(() -> RessourceIntrouvable.de("Colis", id));
    }

    private Lieu charger(Long id) {
        return lieux.findById(id).orElseThrow(() -> RessourceIntrouvable.de("Lieu", id));
    }

    /** Une expédition avec ses colis. Convertie dans la transaction. */
    @Transactional(readOnly = true)
    public VueExpedition vue(Long expeditionId) {
        return VueExpedition.complete(expeditions.findById(expeditionId)
                .orElseThrow(() -> RessourceIntrouvable.de("Expédition", expeditionId)));
    }

    /** Remplit un colis, et renvoie un DTO plutôt que l'entité. */
    @Transactional
    public VueLigneColis remplirEtResumer(Long colisId, Long ligneCommandeId, int quantite) {
        return VueLigneColis.de(remplir(colisId, ligneCommandeId, quantite), colisId);
    }

    /**
     * La liste du back-office : toutes les expéditions, filtrables.
     *
     * <p>Elle répond à « qu'est-ce qui est en route ? » et à « qu'est-ce qui
     * attend un départ ? ». Sans filtre par statut, ces deux questions
     * demanderaient de parcourir toutes les pages.</p>
     *
     * <p>La requête assemble le numéro de commande et le point de récupération
     * en <b>une seule passe</b>, jointures comprises — pas une lecture par
     * ligne.</p>
     */
    @Transactional(readOnly = true)
    public Page<ResumeExpedition> administration(StatutExpedition statut, String recherche,
                                                 Pageable pagination) {
        String filtre = (recherche == null || recherche.isBlank()) ? null : recherche.strip();
        return expeditions.administration(statut, filtre, pagination);
    }

    /**
     * Ce qui est déjà parti pour une commande.
     *
     * <p>La fiche commande a besoin de le savoir avant de proposer d'expédier
     * une fois de plus : sans cette liste, rien n'empêcherait d'envoyer deux
     * fois la même marchandise.</p>
     */
    @Transactional(readOnly = true)
    public List<ResumeExpedition> parCommande(Long commandeId) {
        return expeditions.resumesParCommande(commandeId);
    }

    /**
     * Le parcours complet d'une expédition : chaque colis avec ses événements.
     *
     * <p>🎯 C'est ce qui distingue un suivi d'un statut. « EN_TRANSIT » ne dit
     * pas où ; « réceptionné à Bertoua le 12/03 à 14 h » le dit, et reste vrai
     * même quand le colis est reparti.</p>
     */
    @Transactional(readOnly = true)
    public List<VueParcoursColis> parcoursComplet(Long expeditionId) {
        Expedition expedition = expeditions.chargerAvecColis(expeditionId)
                .orElseThrow(() -> RessourceIntrouvable.de("Expédition", expeditionId));

        return expedition.getColis().stream()
                .map(c -> new VueParcoursColis(
                        c.getId(), c.getNumeroSuivi(), c.getPoidsKg(), c.getStatut().name(),
                        evenements.findByColisIdOrderByDateHeureAsc(c.getId()).stream()
                                .map(VueEvenement::de)
                                .toList()))
                .toList();
    }

    /**
     * Où en est la marchandise de cette commande.
     *
     * <p>🎯 LA règle de lecture, écrite une seule fois. La commande en déduit
     * son statut — elle ne le reçoit plus d'un clic.</p>
     *
     * <p>⚠️ Vide la session AVANT de compter : la requête est en SQL natif,
     * et le départ ou la mise en colis qu'on vient d'enregistrer n'est encore
     * qu'en mémoire. Sans ce vidage, on compterait l'état d'avant.</p>
     */
    @Transactional
    public AvancementCommande avancement(Long commandeId) {
        expeditions.flush();
        Object[] l = expeditions.avancement(commandeId).getFirst();

        if (nombre(l[0]) == 0) {
            return AvancementCommande.RIEN;
        }
        if (nombre(l[1]) > 0) {
            return AvancementCommande.EN_PREPARATION;
        }
        if (nombre(l[2]) > 0) {
            return AvancementCommande.PRETE;
        }
        if (nombre(l[3]) > 0) {
            return AvancementCommande.EXPEDIEE;
        }
        if (nombre(l[4]) > 0) {
            return AvancementCommande.DISPONIBLE;
        }
        return AvancementCommande.REMISE;
    }

    private static long nombre(Object valeur) {
        return ((Number) valeur).longValue();
    }
}
