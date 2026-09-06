package com.garah.api.surveillance.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un fait de sécurité, daté et immuable.
 *
 * <p><b>Point d'architecture à remarquer</b> (chapitre 06 §4.3) :
 * {@code utilisateurId} est un simple {@code Long}, PAS une association vers
 * l'entité {@code Utilisateur} du domaine IAM.</p>
 *
 * <p>C'est délibéré. Le domaine IAM a besoin de la surveillance (pour
 * enregistrer un échec de connexion). Si la surveillance référençait en retour
 * les entités IAM, on créerait un <b>cycle</b> — et ArchUnit échouerait au
 * build. Un identifiant opaque casse le cycle sans rien perdre : la clé
 * étrangère reste garantie par la base.</p>
 */
@Entity
@Table(name = "evenement_securite")
public class EvenementSecurite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "utilisateur_id")
    private Long utilisateurId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TypeEvenementSecurite type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GraviteEvenement gravite = GraviteEvenement.INFO;

    @Column(name = "appareil_id")
    private Long appareilId;

    /**
     * Adresse IP d'origine, en texte (migration V15).
     *
     * <p>La colonne était en {@code inet}, le type « juste » de PostgreSQL.
     * Mais la base ne convertit pas implicitement {@code varchar → inet}, et
     * l'insertion échouait. Le compromis est expliqué dans V15.</p>
     */
    @Column(name = "adresse_ip", length = 45)
    private String adresseIp;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "date_heure", nullable = false, updatable = false)
    private Instant dateHeure = Instant.now();

    protected EvenementSecurite() {
    }

    public EvenementSecurite(Long utilisateurId, TypeEvenementSecurite type,
                             GraviteEvenement gravite, String adresseIp, String description) {
        this.utilisateurId = utilisateurId;
        this.type = type;
        this.gravite = gravite;
        this.adresseIp = adresseIp;
        this.description = description;
    }

    public Long getId() { return id; }
    public Long getUtilisateurId() { return utilisateurId; }
    public TypeEvenementSecurite getType() { return type; }
    public GraviteEvenement getGravite() { return gravite; }
    public String getAdresseIp() { return adresseIp; }
    public String getDescription() { return description; }
    public Instant getDateHeure() { return dateHeure; }
}
