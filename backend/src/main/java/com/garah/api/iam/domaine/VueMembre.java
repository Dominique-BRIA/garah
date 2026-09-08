package com.garah.api.iam.domaine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Un membre de l'équipe, vu du back-office.
 *
 * <p>Réunit ce que le schéma sépare : {@code utilisateur} porte l'identité et
 * la connexion, {@code responsable} porte le matricule et l'emploi. La
 * distinction est juste en base — un ADMIN n'a pas de matricule — mais elle
 * n'intéresse pas l'écran, qui affiche une personne.</p>
 *
 * @param matricule {@code null} pour un ADMIN ou un SUPER_ADMIN : ils agissent
 *                  sur le système, ils ne sont pas affectés à un poste
 * @param titre le nom de la catégorie principale. Il n'existe volontairement
 *              aucun champ « titre » en base : il finirait par diverger du
 *              profil réel
 * @param urlPhoto l'adresse de la photo, <b>déjà signée</b>, ou {@code null}.
 *                 La base ne range qu'une clé d'objet (D-21) : le frontend ne
 *                 peut pas fabriquer cette adresse, il faudrait signer, donc
 *                 détenir la clé secrète.
 */
public record VueMembre(
        Long id,
        String type,
        String nom,
        String prenom,
        String email,
        String telephone,
        String statut,
        boolean emailVerifie,
        String matricule,
        LocalDate dateEmbauche,
        String titre,
        String urlPhoto,
        List<VueProfil> profils,
        Instant dateCreation,
        Instant dateDerniereConnexion) {

    /**
     * Un ADMIN ou un SUPER_ADMIN : aucun poste, aucun profil.
     *
     * @param urls ce qui transforme une clé d'objet en adresse signée.
     *             Passé en fonction plutôt qu'en dépendance : cette vue est
     *             un enregistrement, elle ne connaît pas le stockage.
     */
    public static VueMembre de(Utilisateur u, UnaryOperator<String> urls) {
        return new VueMembre(u.getId(), u.getType().name(), u.getNom(), u.getPrenom(),
                u.getEmail(), u.getTelephone(), u.getStatut().name(), u.estEmailVerifie(),
                null, null, null, urls.apply(u.getPhotoCle()),
                List.of(), u.getDateCreation(), u.getDateDerniereConnexion());
    }

    /**
     * Un RESPONSABLE, avec ses profils.
     *
     * <p>⚠️ À n'appeler que sur un responsable dont les catégories ont été
     * chargées. Les collections sont {@code LAZY} : hors transaction, ou sans
     * {@code JOIN FETCH}, cette méthode lèverait — et seulement à l'exécution.</p>
     */
    public static VueMembre de(Responsable r, UnaryOperator<String> urls) {
        Utilisateur u = r.getUtilisateur();

        List<VueProfil> profils = r.getCategories().stream()
                .map(rc -> VueProfil.resume(rc.getCategorie(), rc.estPrincipale(), rc.estChef()))
                .toList();

        return new VueMembre(u.getId(), u.getType().name(), u.getNom(), u.getPrenom(),
                u.getEmail(), u.getTelephone(), r.getStatut().name(), u.estEmailVerifie(),
                r.getMatricule(), r.getDateEmbauche(), r.titre().orElse(null),
                urls.apply(u.getPhotoCle()), profils,
                u.getDateCreation(), u.getDateDerniereConnexion());
    }
}
