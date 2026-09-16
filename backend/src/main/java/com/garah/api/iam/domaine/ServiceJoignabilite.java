package com.garah.api.iam.domaine;

import com.garah.api.iam.infra.IdentiteSocialeRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Peut-on joindre ce client au sujet de sa commande ?
 *
 * <h2>🎯 La règle que D-23 voulait vraiment poser</h2>
 *
 * <p>D-23 exigeait une <b>adresse e-mail confirmée</b> avant de commander. Mais
 * sa propre justification ne parlait pas d'e-mail :</p>
 *
 * <blockquote>
 * « le numéro de commande, le code de retrait, les avis d'acheminement partent
 * tous à cette adresse […] la marchandise arrive à Bangui sans que personne ne
 * puisse être prévenu »
 * </blockquote>
 *
 * <p>Ce qui était exigé, c'est d'être <b>joignable</b>. L'e-mail n'en était que
 * le seul moyen disponible à l'époque — il en existe un second depuis V39 :</p>
 *
 * <pre>
 * e-mail confirme       le client a clique le lien qu on lui a envoye
 * numero WhatsApp       le client a recopie le code qu on lui a envoye
 * </pre>
 *
 * <p>Les deux prouvent exactement la même chose : <b>quelqu'un contrôle ce
 * canal, et nous saurons l'atteindre</b>. Sur l'axe Douala → Bangui, le second
 * est même le plus sûr des deux — beaucoup de clients n'ouvrent jamais leur
 * boîte mail.</p>
 *
 * <h2>⚠️ Ce qui ne compte PAS comme preuve</h2>
 *
 * <p>La colonne {@code utilisateur.telephone} <b>ne prouve rien</b> : elle a
 * été saisie dans un formulaire, sans le moindre contrôle. Un numéro mal tapé y
 * ressemble à un numéro juste. Seule une identité {@code WHATSAPP} atteste le
 * numéro, parce qu'elle n'existe que si un code envoyé à ce numéro a été
 * recopié.</p>
 *
 * <p>C'est la même distinction que pour le rattachement des comptes : on ne
 * fait jamais reposer une décision sur une donnée que l'utilisateur a
 * simplement <b>déclarée</b>.</p>
 */
@Service
public class ServiceJoignabilite {

    private final UtilisateurRepository utilisateurs;
    private final IdentiteSocialeRepository identites;

    public ServiceJoignabilite(UtilisateurRepository utilisateurs,
                               IdentiteSocialeRepository identites) {
        this.utilisateurs = utilisateurs;
        this.identites = identites;
    }

    /**
     * Au moins un canal prouvé.
     *
     * <p>Un compte inconnu n'est pas joignable — et non « joignable par
     * défaut » : le doute se tranche du côté qui refuse.</p>
     */
    @Transactional(readOnly = true)
    public boolean estJoignable(Long utilisateurId) {
        if (utilisateurId == null) {
            return false;
        }

        boolean emailConfirme = utilisateurs.findById(utilisateurId)
                .map(Utilisateur::estEmailVerifie)
                .orElse(false);

        return emailConfirme || numeroProuve(utilisateurId);
    }

    /** Une identité WhatsApp existe : le numéro a reçu un code, et il a été recopié. */
    @Transactional(readOnly = true)
    public boolean numeroProuve(Long utilisateurId) {
        return identites.existsByUtilisateurIdAndFournisseur(
                utilisateurId, FournisseurIdentite.WHATSAPP);
    }
}
