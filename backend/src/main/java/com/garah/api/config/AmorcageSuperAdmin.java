package com.garah.api.config;

import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crée le premier SuperAdmin au démarrage, s'il n'en existe aucun.
 *
 * <p><b>Le problème qu'il résout : l'amorçage.</b> Toutes les routes exigent un
 * jeton. Un jeton s'obtient en se connectant. Se connecter demande un compte.
 * Créer un compte demande… un jeton. Il faut donc un point d'entrée unique.</p>
 *
 * <p><b>Ce qu'on ne fait surtout pas :</b> écrire un compte par défaut dans une
 * migration. Un mot de passe versionné dans git est un mot de passe public — et
 * la migration serait rejouée à l'identique sur chaque environnement, production
 * comprise.</p>
 *
 * <p>Ici, les identifiants viennent de l'environnement, et rien ne se passe si
 * les variables sont absentes.</p>
 */
@Component
public class AmorcageSuperAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AmorcageSuperAdmin.class);

    private final UtilisateurRepository utilisateurs;
    private final PasswordEncoder encodeur;
    private final String email;
    private final String motDePasse;
    private final String nom;

    public AmorcageSuperAdmin(UtilisateurRepository utilisateurs,
                              PasswordEncoder encodeur,
                              @Value("${GARAH_SUPERADMIN_EMAIL:}") String email,
                              @Value("${GARAH_SUPERADMIN_MOT_DE_PASSE:}") String motDePasse,
                              @Value("${GARAH_SUPERADMIN_NOM:Super Administrateur}") String nom) {
        this.utilisateurs = utilisateurs;
        this.encodeur = encodeur;
        this.email = email;
        this.motDePasse = motDePasse;
        this.nom = nom;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (email.isBlank() || motDePasse.isBlank()) {
            return;
        }

        // Idempotent : redémarrer l'application ne doit rien recréer, et
        // surtout ne pas réinitialiser un mot de passe déjà changé.
        if (!utilisateurs.findByType(TypeUtilisateur.SUPER_ADMIN).isEmpty()) {
            return;
        }

        utilisateurs.save(new Utilisateur(
                TypeUtilisateur.SUPER_ADMIN, nom, email, encodeur.encode(motDePasse)));

        log.warn("""
                SuperAdmin initial cree pour {}. \
                CHANGEZ CE MOT DE PASSE, et retirez les variables GARAH_SUPERADMIN_* \
                de l'environnement une fois la connexion verifiee.""", email);
    }
}
