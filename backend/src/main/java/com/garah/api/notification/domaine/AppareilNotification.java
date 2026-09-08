package com.garah.api.notification.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un appareil abonné aux notifications.
 *
 * <h2>🎯 Le jeton appartient à un COMPTE, pas à un téléphone</h2>
 *
 * <p>C'est toute la difficulté. Un jeton FCM désigne une <b>installation
 * d'application</b>, pas une personne : sur un téléphone partagé — ce qui est
 * la norme et non l'exception sur le corridor Douala-Bangui — deux clients se
 * succèdent derrière le même jeton.</p>
 *
 * <p>D'où la clé primaire sur le <b>jeton seul</b>. Il ne peut appartenir qu'à
 * un compte à la fois : quand le suivant se connecte, la même ligne change de
 * propriétaire. Une clé composite {@code (utilisateur, jeton)} aurait laissé
 * les deux inscriptions vivre côte à côte, et « votre marchandise vous
 * attend » serait parti au précédent — pour un colis qui n'est pas le sien.</p>
 *
 * <p>⚠️ Ce module ne connaît <b>aucun</b> autre : il ne manipule qu'un
 * identifiant d'utilisateur, jamais l'entité. Savoir à qui écrire n'est pas
 * savoir qui c'est.</p>
 */
@Entity
@Table(name = "appareil_notification")
public class AppareilNotification {

    /** Ce que Google nous rend. Aucune taille n'est garantie : on prévoit large. */
    @Id
    @Column(nullable = false, length = 500)
    private String jeton;

    @Column(name = "utilisateur_id", nullable = false)
    private Long utilisateurId;

    @Column(nullable = false, length = 20)
    private String plateforme;

    /**
     * Sert au <b>ménage</b>, pas à la statistique.
     *
     * <p>Un jeton qu'on n'a pas revu depuis des mois désigne une application
     * désinstallée. Le garder ferait grossir la table indéfiniment et
     * facturerait des envois qui échouent tous.</p>
     */
    @Column(name = "date_maj", nullable = false)
    private Instant dateMaj = Instant.now();

    protected AppareilNotification() {
    }

    public AppareilNotification(String jeton, Long utilisateurId, String plateforme) {
        this.jeton = jeton;
        this.utilisateurId = utilisateurId;
        this.plateforme = plateforme;
    }

    /**
     * Le même appareil, désormais à quelqu'un d'autre.
     *
     * <p>C'est le cas du téléphone partagé, et il ne doit surtout pas créer une
     * seconde ligne.</p>
     */
    public void rattacherA(Long utilisateurId, String plateforme) {
        this.utilisateurId = utilisateurId;
        this.plateforme = plateforme;
        this.dateMaj = Instant.now();
    }

    public String getJeton() { return jeton; }
    public Long getUtilisateurId() { return utilisateurId; }
    public String getPlateforme() { return plateforme; }
    public Instant getDateMaj() { return dateMaj; }
}
