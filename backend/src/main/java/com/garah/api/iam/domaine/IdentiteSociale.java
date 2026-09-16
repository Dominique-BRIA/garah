package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Le lien entre un compte GARAH et une identité chez un fournisseur.
 *
 * <p>🎯 <b>La clé d'identité est le {@code sujet}, jamais l'adresse
 * e-mail.</b> Une adresse change, et peut être réattribuée à quelqu'un
 * d'autre — chez Google Workspace, un départ suivi d'une embauche homonyme
 * suffit. Le {@code sub} d'un fournisseur OIDC, lui, désigne la même personne
 * à vie.</p>
 *
 * <p>L'adresse est tout de même conservée, mais uniquement comme
 * <b>photographie</b> : elle répond à « pourquoi ce compte a-t-il été
 * rattaché ce jour-là ? » et n'est jamais relue pour identifier quelqu'un.</p>
 */
@Entity
@Table(name = "identite_sociale")
public class IdentiteSociale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id")
    private Utilisateur utilisateur;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FournisseurIdentite fournisseur;

    /** Le {@code sub} du jeton. Unique par fournisseur (index en V38). */
    @Column(nullable = false, length = 255)
    private String sujet;

    @Column(name = "email_annonce", length = 255)
    private String emailAnnonce;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_derniere_utilisation")
    private Instant dateDerniereUtilisation;

    protected IdentiteSociale() {
    }

    public IdentiteSociale(Utilisateur utilisateur, FournisseurIdentite fournisseur,
                           String sujet, String emailAnnonce) {
        this.utilisateur = utilisateur;
        this.fournisseur = fournisseur;
        this.sujet = sujet;
        this.emailAnnonce = emailAnnonce;
        this.dateDerniereUtilisation = Instant.now();
    }

    public Long getId() { return id; }
    public Utilisateur getUtilisateur() { return utilisateur; }
    public FournisseurIdentite getFournisseur() { return fournisseur; }
    public String getSujet() { return sujet; }
    public String getEmailAnnonce() { return emailAnnonce; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateDerniereUtilisation() { return dateDerniereUtilisation; }

    /**
     * Marque l'usage, et rafraîchit l'adresse annoncée.
     *
     * <p>Rafraîchir n'a aucun effet sur l'identification — on vient déjà de
     * retrouver le compte par le {@code sujet}. Cela sert à savoir, six mois
     * plus tard, quelle adresse le fournisseur annonçait la dernière fois.</p>
     */
    public void marquerUtilisee(String emailAnnonce) {
        this.dateDerniereUtilisation = Instant.now();
        if (emailAnnonce != null && !emailAnnonce.isBlank()) {
            this.emailAnnonce = emailAnnonce;
        }
    }
}
