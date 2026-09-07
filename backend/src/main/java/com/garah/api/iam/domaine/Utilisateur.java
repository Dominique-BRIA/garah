package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * La racine de toute identité dans GARAH.
 *
 * <p><b>Choix de conception : pas d'héritage JPA.</b> Le schéma a bien une
 * relation d'héritage ({@code client.id} et {@code responsable.id} sont des
 * clés étrangères vers {@code utilisateur.id}), mais on ne l'exprime PAS avec
 * {@code @Inheritance(JOINED)}. Deux raisons :</p>
 *
 * <ol>
 *   <li>Un {@code ADMIN} n'a pas de table fille. Avec un discriminateur JPA,
 *       la classe racine devrait avoir une seule valeur de discriminateur —
 *       or elle en a deux ({@code ADMIN} et {@code SUPER_ADMIN}).</li>
 *   <li>L'héritage JPA rend toute requête polymorphe : charger un utilisateur
 *       déclenche des jointures vers toutes les tables filles, même quand on
 *       n'en a pas besoin.</li>
 * </ol>
 *
 * <p>On utilise donc la <b>composition</b> : {@link Client} et
 * {@link Responsable} référencent leur utilisateur avec {@code @MapsId}.
 * C'est plus explicite, plus prévisible, et ça exprime exactement ce que
 * fait le schéma.</p>
 */
@Entity
@Table(name = "utilisateur")
public class Utilisateur {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stocké en texte, jamais en {@code ORDINAL}.
     *
     * <p>Avec {@code ORDINAL}, JPA écrirait 0, 1, 2… Insérer une valeur au
     * milieu de l'enum décalerait silencieusement toutes les lignes existantes.
     * La base contiendrait des données fausses sans qu'aucune erreur
     * n'apparaisse. {@code STRING} rend cette catastrophe impossible — et il
     * est de toute façon imposé ici par la contrainte {@code CHECK}.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeUtilisateur type;

    @Column(nullable = false, length = 100)
    private String nom;

    @Column(length = 100)
    private String prenom;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 30)
    private String telephone;

    /**
     * Empreinte du mot de passe, jamais le mot de passe.
     *
     * <p>Ce champ ne doit JAMAIS sortir du package {@code domaine}. C'est la
     * raison n°1 pour laquelle une entité n'est jamais renvoyée telle quelle
     * par un contrôleur (chapitre 06, piège 5).</p>
     */
    @Column(name = "mot_de_passe", nullable = false, length = 255)
    private String motDePasse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutUtilisateur statut = StatutUtilisateur.ACTIF;

    @Column(name = "deux_facteurs_actif", nullable = false)
    private boolean deuxFacteursActif = false;

    /**
     * L'adresse a-t-elle été confirmée par un lien ? (D-23)
     *
     * <p>Faux par défaut : tout compte naît non vérifié. Seuls les CLIENT
     * s'inscrivent eux-mêmes ; les comptes créés par un administrateur sont
     * marqués vérifiés à la création, puisque quelqu'un a déjà répondu de
     * leur identité.</p>
     */
    @Column(name = "email_verifie", nullable = false)
    private boolean emailVerifie = false;

    /**
     * Code de la langue préférée. Mappé en {@code String} et non en entité :
     * {@code langue} est un référentiel de trois lignes qu'on ne joint jamais.
     * La clé étrangère reste garantie par la base.
     */
    @Column(nullable = false, length = 2)
    private String langue = "fr";

    /**
     * La clé d'objet de la photo de profil. {@code null} = pas de photo.
     *
     * <p>⚠️ Une <b>clé</b>, jamais une URL. Le bucket est privé et les URL
     * signées expirent au bout de sept jours (D-21) : en stocker une ici
     * donnerait une base pleine d'adresses mortes au bout d'une semaine, et
     * changer d'hébergeur de fichiers obligerait à réécrire la table.</p>
     *
     * <p>La colonne existait depuis V23 mais n'était mappée par aucun champ :
     * la photo pouvait être déposée sur le stockage, rien ne la reliait au
     * compte.</p>
     */
    @Column(name = "photo_cle", length = 500)
    private String photoCle;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_modification")
    private Instant dateModification;

    @Column(name = "date_derniere_connexion")
    private Instant dateDerniereConnexion;

    protected Utilisateur() {
        // Requis par JPA. Volontairement protected : le code applicatif doit
        // passer par le constructeur qui exige les champs obligatoires.
    }

    public Utilisateur(TypeUtilisateur type, String nom, String email, String motDePasse) {
        this.type = type;
        this.nom = nom;
        this.email = email;
        this.motDePasse = motDePasse;
    }

    @PreUpdate
    void avantMiseAJour() {
        this.dateModification = Instant.now();
    }

    public boolean estEmailVerifie() {
        return emailVerifie;
    }

    /**
     * Confirme l'adresse. Il n'y a pas de chemin inverse, et c'est voulu :
     * « dévérifier » une adresse n'a aucun sens métier. Un changement
     * d'adresse remettra le drapeau à faux en passant par le constructeur
     * du changement, jamais par un setter public.
     */
    public void marquerEmailVerifie() {
        this.emailVerifie = true;
    }

    public boolean estActif() {
        return statut == StatutUtilisateur.ACTIF;
    }

    public Long getId() { return id; }
    public TypeUtilisateur getType() { return type; }
    public String getNom() { return nom; }
    public String getPrenom() { return prenom; }
    public String getEmail() { return email; }
    public String getTelephone() { return telephone; }
    public String getMotDePasse() { return motDePasse; }
    public StatutUtilisateur getStatut() { return statut; }
    public boolean isDeuxFacteursActif() { return deuxFacteursActif; }
    public String getLangue() { return langue; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateDerniereConnexion() { return dateDerniereConnexion; }

    /**
     * Change le nom affiché.
     *
     * <p>Une méthode nommée plutôt qu'un {@code setNom} : le nom est le seul
     * champ obligatoire du constructeur, et il ne doit jamais pouvoir devenir
     * vide par un appel distrait. La règle vit donc ici, avec la donnée, et
     * non chez chacun de ses appelants.</p>
     */
    public void renommer(String nom) {
        if (nom == null || nom.isBlank()) {
            throw new IllegalArgumentException("Le nom ne peut pas être vide.");
        }
        this.nom = nom.strip();
    }

    public String getPhotoCle() { return photoCle; }
    public void setPhotoCle(String photoCle) { this.photoCle = photoCle; }

    public void setPrenom(String prenom) { this.prenom = prenom; }
    public void setTelephone(String telephone) { this.telephone = telephone; }
    public void setLangue(String langue) { this.langue = langue; }
    public void setMotDePasse(String motDePasse) { this.motDePasse = motDePasse; }
    public void setStatut(StatutUtilisateur statut) { this.statut = statut; }
    public void setDeuxFacteursActif(boolean actif) { this.deuxFacteursActif = actif; }
    public void setDateDerniereConnexion(Instant date) { this.dateDerniereConnexion = date; }
}
