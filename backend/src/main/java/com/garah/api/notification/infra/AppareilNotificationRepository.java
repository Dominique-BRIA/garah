package com.garah.api.notification.infra;

import com.garah.api.notification.domaine.AppareilNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AppareilNotificationRepository
        extends JpaRepository<AppareilNotification, String> {

    /**
     * A qui dois-je ecrire ? C est LA question posee a chaque envoi.
     *
     * <p>Une personne a souvent plusieurs appareils — un telephone et un
     * navigateur. On ecrit a tous : deviner lequel elle regarde reviendrait a
     * ne pas la prevenir une fois sur deux.</p>
     */
    List<AppareilNotification> findByUtilisateurId(Long utilisateurId);

    /**
     * Le menage.
     *
     * <p>⚠️ Un jeton qu on n a pas revu depuis des mois designe une application
     *    DESINSTALLEE. Le garder ferait grossir la table indefiniment, et
     *    chaque envoi vers un jeton mort est un appel facture qui echoue.</p>
     */
    long deleteByDateMajBefore(Instant limite);
}
