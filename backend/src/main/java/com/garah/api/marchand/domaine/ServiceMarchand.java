package com.garah.api.marchand.domaine;

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

    /**
     * Crée un marchand. Le code est ENGENDRÉ, jamais saisi.
     *
     * <p>Il était tapé à la main, et cela produisait « 202020 » : une valeur
     * qui ne dit rien, impossible à dicter sans se tromper, et qu'il fallait
     * inventer à chaque création. Une séquence donne MAR-00001, lisible et
     * sans collision possible.</p>
     */
    @Transactional
    public VueMarchand creer(String nom, TypeMarchand type, String pays,
                             String telephone, String email) {

        Marchand marchand = new Marchand(genererCode(), nom.strip(), type);
        marchand.setPays(normaliserPays(pays));
        marchand.setTelephone(vide(telephone) ? null : telephone.strip());
        marchand.setEmail(vide(email) ? null : email.strip());

        return VueMarchand.de(marchands.save(marchand));
    }

    @Transactional
    public VueMarchand modifier(Long id, String nom, String pays,
                                String telephone, String email) {
        Marchand marchand = charger(id);

        marchand.setNom(nom.strip());
        marchand.setPays(normaliserPays(pays));
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

    /** {@code MAR-00042}, tiré d'une séquence PostgreSQL (V23). */
    private String genererCode() {
        return "MAR-%05d".formatted(marchands.prochainCode());
    }

    /**
     * Le pays, en deux lettres majuscules.
     *
     * <p>La base refuse tout ce qui ne correspond pas à deux lettres majuscules.
     * On normalise ici pour produire un message clair plutôt qu'une erreur
     * d'intégrité — et parce que « cm » saisi en minuscules est une faute de
     * frappe, pas une intention.</p>
     */
    private static String normaliserPays(String pays) {
        String propre = pays == null ? "" : pays.strip().toUpperCase(Locale.ROOT);
        if (!propre.matches("[A-Z]{2}")) {
            throw new RegleMetierViolee("PAYS_INVALIDE",
                    "Le pays doit être un code à deux lettres (CM, CF, TD…).");
        }
        return propre;
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}
