package com.garah.api.iam.web;

import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.springframework.stereotype.Component;

/**
 * « Est-ce qu'on s'apprête à toucher à un ADMINISTRATEUR ? »
 *
 * <h2>🎯 Le trou que cette classe ferme</h2>
 *
 * <p>{@code ControleurEquipe.creer} le demandait déjà : créer un compte ADMIN
 * exige {@code ADMIN_CREER}, en plus de {@code RESPONSABLE_CREER}. Sans quoi
 * un responsable à qui un profil accorde la création de comptes pouvait
 * fabriquer un administrateur et s'y connecter.</p>
 *
 * <p>Le même raisonnement manquait un cran plus loin. <b>Modifier</b>,
 * <b>activer</b>, <b>désactiver</b> un compte et lui <b>réaffecter des
 * profils</b> ne regardaient que les droits {@code RESPONSABLE_*}. Un
 * responsable pouvait donc désactiver un administrateur — c'est-à-dire couper
 * l'accès de quelqu'un placé au-dessus de lui.</p>
 *
 * <p>{@code ADMIN_MODIFIER}, {@code ADMIN_ACTIVER} et {@code ADMIN_DESACTIVER}
 * existent au référentiel depuis l'origine, dans le module {@code SECURITE} —
 * donc réservés au super-administrateur. Ils n'étaient vérifiés <b>nulle
 * part</b>. Un droit déclaré et jamais appliqué ne protège rien, et se lit
 * pourtant comme une protection.</p>
 *
 * <h2>⚠️ Pourquoi une classe, et pas une expression</h2>
 *
 * <p>{@code @PreAuthorize} ne reçoit que l'identifiant : il ne peut pas savoir
 * ce qu'il désigne sans lire la base. C'est ce que fait cette classe, appelée
 * depuis l'expression sous le nom {@code @gardeEquipe}.</p>
 *
 * <p>Elle coûte une requête sur ces routes-là. C'est le bon prix : modifier ou
 * désactiver un membre est un geste rare, et le chemin chaud de l'application
 * ne passe pas par ici.</p>
 *
 * <p>⚠️ Cette requête rend un <b>booléen</b>, pas l'utilisateur. Le test
 * d'architecture interdit à la couche web de toucher une entité — exposer une
 * entité, c'est publier son schéma, et un mot de passe finit un jour dans une
 * réponse JSON. Il a d'ailleurs attrapé la première version de cette
 * classe.</p>
 */
@Component("gardeEquipe")
public class GardeEquipe {

    private final UtilisateurRepository utilisateurs;

    public GardeEquipe(UtilisateurRepository utilisateurs) {
        this.utilisateurs = utilisateurs;
    }

    /**
     * Le compte visé est-il un administrateur ?
     *
     * <p>⚠️ Un identifiant inconnu rend {@code false}, et non une erreur : le
     * refus doit venir de la couche qui sait dire « introuvable », pas d'une
     * expression d'autorisation. Sinon on répondrait « accès refusé » là où la
     * bonne réponse est « ce membre n'existe pas » — et l'on chercherait un
     * problème de droits pendant que le vrai sujet est une faute de frappe.</p>
     */
    public boolean cibleUnAdmin(Long id) {
        return utilisateurs.existsByIdAndType(id, TypeUtilisateur.ADMIN);
    }
}
