package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un code à usage unique, envoyé sur WhatsApp.
 *
 * <p>⚠️ Le code n'est <b>jamais</b> stocké en clair : seule son empreinte
 * l'est. Six chiffres qui vivent cinq minutes paraissent anodins, mais
 * quiconque lirait cette table — une sauvegarde, une console SQL, un export —
 * pourrait se connecter en tant que n'importe qui pendant ces cinq minutes,
 * <b>sans jamais toucher au téléphone de la victime</b>.</p>
 *
 * <p>C'est le raisonnement qui interdit de stocker un mot de passe en clair,
 * avec une fenêtre plus courte — et une fenêtre courte n'est pas une
 * protection, seulement un délai.</p>
 */
@Entity
@Table(name = "code_connexion")
public class CodeConnexion {

    /**
     * Au-delà, le code est mort même s'il n'a jamais servi.
     *
     * <p>Cinq minutes : assez pour recevoir un message et le recopier sur une
     * connexion camerounaise lente, trop peu pour qu'un code intercepté serve
     * le lendemain.</p>
     */
    public static final int MINUTES_VALIDITE = 5;

    /**
     * Après quoi le code est brûlé, même s'il était bon.
     *
     * <p>Six chiffres, c'est un million de combinaisons — quelques minutes de
     * script. Le plafond est ce qui rend l'attaque impossible, pas la longueur
     * du code.</p>
     */
    public static final int TENTATIVES_MAX = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalisé E.164. Aucune clé étrangère : le compte n'existe pas encore. */
    @Column(nullable = false, length = 20)
    private String telephone;

    @Column(name = "code_hache", nullable = false, length = 255)
    private String codeHache;

    @Column(name = "date_expiration", nullable = false)
    private Instant dateExpiration;

    @Column(nullable = false)
    private int tentatives = 0;

    @Column(nullable = false)
    private boolean consomme = false;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    protected CodeConnexion() {
    }

    public CodeConnexion(String telephone, String codeHache) {
        this.telephone = telephone;
        this.codeHache = codeHache;
        this.dateExpiration = Instant.now().plusSeconds(MINUTES_VALIDITE * 60L);
    }

    public Long getId() { return id; }
    public String getTelephone() { return telephone; }
    public String getCodeHache() { return codeHache; }
    public int getTentatives() { return tentatives; }
    public boolean estConsomme() { return consomme; }

    public boolean estExpire() {
        return Instant.now().isAfter(dateExpiration);
    }

    /**
     * Utilisable ? Trois conditions, et il faut les trois.
     *
     * <p>Les tester séparément à l'appel finirait par en oublier une — c'est
     * typiquement le plafond de tentatives qu'on oublie, parce qu'il ne se
     * manifeste jamais pendant les essais.</p>
     */
    public boolean estUtilisable() {
        return !consomme && !estExpire() && tentatives < TENTATIVES_MAX;
    }

    public void compterUnEchec() {
        this.tentatives++;

        // ⚠️ Au plafond, on CONSOMME le code plutôt que de se contenter de
        //    refuser. Sans cela, il resterait « vivant » pour l'index unique,
        //    et la personne ne pourrait plus en demander un nouveau : elle
        //    serait enfermée dehors par sa propre faute de frappe.
        if (this.tentatives >= TENTATIVES_MAX) {
            this.consomme = true;
        }
    }

    public void consommer() {
        this.consomme = true;
    }
}
