package com.garah.api.marchand.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.marchand.infra.MarchandRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Les marchands : GARAH elle-même, et les partenaires qui vendent via elle.
 *
 * <p>Sans eux, aucun produit ne peut exister — et donc aucun catalogue, aucune
 * commande, aucune vente. C'est le premier maillon de la chaîne.</p>
 */
@Service
public class ServiceMarchand {

    private final MarchandRepository marchands;

    public ServiceMarchand(MarchandRepository marchands) {
        this.marchands = marchands;
    }

    @Transactional
    public VueMarchand creer(String code, String nom, TypeMarchand type,
                             String telephone, String email) {

        String reference = normaliser(code);

        // Vérification AVANT l'insertion, pour un message clair. La contrainte
        // UNIQUE reste la vraie garantie : deux créations simultanées
        // passeraient toutes deux ce test, et la base en refusera une.
        if (marchands.existsByCodeIgnoreCase(reference)) {
            throw new ConflitEtat("CODE_MARCHAND_EXISTANT",
                    "Un marchand porte déjà le code « " + reference + " ».");
        }

        Marchand marchand = new Marchand(reference, nom.strip(), type);
        marchand.setTelephone(vide(telephone) ? null : telephone.strip());
        marchand.setEmail(vide(email) ? null : email.strip());

        return VueMarchand.de(marchands.save(marchand));
    }

    @Transactional
    public VueMarchand modifier(Long id, String nom, String telephone, String email) {
        Marchand marchand = charger(id);

        marchand.setNom(nom.strip());
        marchand.setTelephone(vide(telephone) ? null : telephone.strip());
        marchand.setEmail(vide(email) ? null : email.strip());

        return VueMarchand.de(marchand);
    }

    /**
     * Désactive un marchand.
     *
     * <p>⚠️ <b>On ne supprime jamais un marchand.</b> Ses ventes passées
     * portent son identifiant, et le grand livre lui doit peut-être encore de
     * l'argent. Une suppression laisserait des lignes de commande orphelines
     * et un solde impossible à régler.</p>
     *
     * <p>Désactiver suffit : le marchand disparaît des listes de création de
     * produit, mais tout ce qu'il a vendu reste lisible.</p>
     */
    @Transactional
    public VueMarchand changerStatut(Long id, StatutMarchand statut) {
        Marchand marchand = charger(id);
        marchand.setStatut(statut);
        return VueMarchand.de(marchand);
    }

    @Transactional(readOnly = true)
    public Page<VueMarchand> lister(String recherche, Pageable pagination) {
        Page<Marchand> page = vide(recherche)
                ? marchands.findAll(pagination)
                : marchands.findByNomContainingIgnoreCaseOrCodeContainingIgnoreCase(
                        recherche.strip(), recherche.strip(), pagination);

        return page.map(VueMarchand::de);
    }

    /**
     * Ceux qu'on peut associer à un nouveau produit.
     *
     * <p>Seulement les actifs : proposer un marchand désactivé dans un
     * formulaire de création reviendrait à laisser créer un produit qu'on ne
     * pourra jamais vendre.</p>
     */
    @Transactional(readOnly = true)
    public Page<VueMarchand> actifs(Pageable pagination) {
        return marchands.findByStatut(StatutMarchand.ACTIF, pagination).map(VueMarchand::de);
    }

    @Transactional(readOnly = true)
    public VueMarchand detail(Long id) {
        return VueMarchand.de(charger(id));
    }

    private Marchand charger(Long id) {
        return marchands.findById(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Marchand", id));
    }

    /**
     * Le code est mis en majuscules et débarrassé des espaces.
     *
     * <p>Sans cela, « m-042 » et « M-042 » deviendraient deux marchands
     * distincts — et le second échouerait seulement plus tard, sur une
     * comparaison insensible à la casse qui, elle, les confondrait.</p>
     */
    private static String normaliser(String code) {
        String propre = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (propre.isBlank()) {
            throw new RegleMetierViolee("CODE_MARCHAND_OBLIGATOIRE",
                    "Le code du marchand est obligatoire.");
        }
        return propre;
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}
