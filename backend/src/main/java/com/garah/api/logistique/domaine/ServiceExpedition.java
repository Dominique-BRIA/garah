package com.garah.api.logistique.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.logistique.infra.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.Year;
import java.util.List;

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

    private final ExpeditionRepository expeditions;
    private final ColisRepository colis;
    private final EvenementExpeditionRepository evenements;
    private final RetraitMarchandiseRepository retraits;
    private final LieuRepository lieux;

    public ServiceExpedition(ExpeditionRepository expeditions, ColisRepository colis,
                             EvenementExpeditionRepository evenements,
                             RetraitMarchandiseRepository retraits, LieuRepository lieux) {
        this.expeditions = expeditions;
        this.colis = colis;
        this.evenements = evenements;
        this.retraits = retraits;
        this.lieux = lieux;
    }

    @Transactional
    public Expedition creer(Long commandeId, Long lieuDepartId, Long pointRecuperationId,
                            Long itineraireId) {
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

        return expeditions.save(expedition);
    }

    @Transactional
    public Colis ajouterColis(Long expeditionId, String numeroSuivi) {
        Expedition expedition = chargerExpedition(expeditionId);

        if (expedition.getStatut() != StatutExpedition.CREEE
                && expedition.getStatut() != StatutExpedition.PREPAREE) {
            throw new ConflitEtat("EXPEDITION_PARTIE",
                    "On ne peut plus ajouter de colis à une expédition déjà partie.");
        }

        return expedition.ajouterColis(numeroSuivi == null ? genererNumeroSuivi() : numeroSuivi);
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
        return chargerColis(colisId).ajouterLigne(ligneCommandeId, quantite);
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
     */
    @Transactional
    public RetraitMarchandise preparerRetrait(Long expeditionId, Long clientId) {
        Expedition expedition = chargerExpedition(expeditionId);

        if (retraits.findByExpeditionId(expeditionId).isPresent()) {
            throw new ConflitEtat("RETRAIT_DEJA_PREPARE",
                    "Un retrait a déjà été préparé pour cette expédition.");
        }
        if (expedition.getStatut() != StatutExpedition.DISPONIBLE) {
            throw new ConflitEtat("EXPEDITION_NON_ARRIVEE",
                    "La marchandise n'est pas encore disponible au point de récupération.");
        }

        return retraits.save(new RetraitMarchandise(expeditionId, clientId, genererCode()));
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
}
