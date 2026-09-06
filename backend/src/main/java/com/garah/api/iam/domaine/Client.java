package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un client de la plateforme.
 *
 * <p>{@code @MapsId} signifie : « ma clé primaire EST la clé étrangère vers
 * utilisateur ». C'est exactement ce que fait le schéma
 * ({@code client.id REFERENCES utilisateur(id)}), sans passer par l'héritage
 * JPA — voir l'explication dans {@link Utilisateur}.</p>
 */
@Entity
@Table(name = "client")
public class Client {

    @Id
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id")
    private Utilisateur utilisateur;

    @Column(name = "code_client", nullable = false, length = 20)
    private String codeClient;

    @Column(name = "date_inscription", nullable = false, updatable = false)
    private Instant dateInscription = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutUtilisateur statut = StatutUtilisateur.ACTIF;

    protected Client() {
    }

    public Client(Utilisateur utilisateur, String codeClient) {
        this.utilisateur = utilisateur;
        this.codeClient = codeClient;
    }

    public Long getId() { return id; }
    public Utilisateur getUtilisateur() { return utilisateur; }
    public String getCodeClient() { return codeClient; }
    public Instant getDateInscription() { return dateInscription; }
    public StatutUtilisateur getStatut() { return statut; }

    public void setStatut(StatutUtilisateur statut) { this.statut = statut; }
}
