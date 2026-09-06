package com.garah.api.commun.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;

/**
 * L'envoi d'e-mails. Une seule classe parle SMTP.
 *
 * <h2>SMTP, et pas l'API d'un fournisseur</h2>
 *
 * <p>Resend, SendGrid, Mailgun proposent tous une API HTTP plus agréable que
 * SMTP. Chacune imposerait son SDK, son format de requête et sa gestion
 * d'erreurs <b>dans notre code</b> — et changer de fournisseur deviendrait une
 * réécriture.</p>
 *
 * <p>SMTP est un protocole, pas un produit. Passer de Brevo à Mailjet, à un
 * serveur du VPS ou à Gmail, c'est changer un hôte et des identifiants. C'est
 * la réversibilité de D-14 appliquée à l'e-mail.</p>
 *
 * <h2>⚠️ Ne jamais faire échouer l'appelant</h2>
 *
 * <p>Un envoi d'e-mail est <b>lent</b> (quelques centaines de millisecondes à
 * plusieurs secondes) et <b>faillible</b> : quota atteint, SMTP injoignable,
 * adresse inexistante. Laisser cette panne remonter annulerait l'inscription
 * du client — alors que son compte est parfaitement valide et qu'il peut
 * redemander un lien.</p>
 *
 * <p>{@link #envoyer} renvoie donc un booléen et ne lève jamais. C'est
 * volontairement l'inverse du reste du code, où l'on préfère échouer fort :
 * ici l'échec de l'accessoire ne doit pas emporter l'essentiel.</p>
 */
@Component
public class PasserelleEmail {

    private static final Logger log = LoggerFactory.getLogger(PasserelleEmail.class);

    /**
     * ⚠️ {@link ObjectProvider} et non une injection directe.
     *
     * <p>Spring Boot ne crée le bean {@code JavaMailSender} que si
     * {@code spring.mail.host} est renseigné. Une injection directe rendrait
     * donc le SMTP OBLIGATOIRE : sans lui, l'application entière refuserait de
     * démarrer — un catalogue inaccessible parce qu'on n'a pas configuré
     * l'envoi d'e-mails.</p>
     *
     * <p>C'était le cas jusqu'à ce que les tests le révèlent. Le bean est
     * désormais optionnel, comme le stockage S3 et Campay : l'absence d'une
     * dépendance externe dégrade une fonction, elle n'abat pas le service.</p>
     */
    private final ObjectProvider<JavaMailSender> expediteur;
    private final String adresseExpediteur;
    private final String nomExpediteur;
    private final boolean configure;

    public PasserelleEmail(ObjectProvider<JavaMailSender> expediteur,
                           @Value("${spring.mail.host:}") String hote,
                           @Value("${GARAH_MAIL_EXPEDITEUR:}") String adresseExpediteur,
                           @Value("${GARAH_MAIL_NOM_EXPEDITEUR:GARAH}") String nomExpediteur,
                           @Value("${garah.mail.active:true}") boolean actif) {

        this.expediteur = expediteur;
        this.adresseExpediteur = adresseExpediteur == null ? "" : adresseExpediteur.strip();
        this.nomExpediteur = nomExpediteur == null || nomExpediteur.isBlank()
                ? "GARAH" : nomExpediteur.strip();

        // Une variable VIDE n'est pas une variable ABSENTE : on teste le
        // contenu, jamais la présence. C'est la leçon de StockageObjet.
        // ⚠️ Le drapeau garah.mail.active PRIME sur tout le reste.
        //
        // Poser spring.mail.host a vide dans la configuration de test ne
        // suffisait pas : spring-dotenv fait entrer GARAH_MAIL_HOST depuis
        // .env avec une precedence superieure, et la suite envoyait de VRAIS
        // e-mails — 293 secondes sur une seule classe, et sept messages du
        // quota consommes a chaque execution.
        //
        // Un drapeau explicite ne depend d aucun ordre de precedence. C est le
        // meme mecanisme que garah.planification.active et
        // garah.limitation-debit.active, qui fonctionnent deja.
        this.configure = actif
                && hote != null && !hote.isBlank()
                && !this.adresseExpediteur.isBlank();

        if (!configure) {
            log.warn("L'envoi d'e-mails n'est pas configure (spring.mail.host / "
                    + "GARAH_MAIL_EXPEDITEUR). Les liens de verification seront ECRITS "
                    + "DANS LES JOURNAUX au lieu d'etre envoyes.");
        }
    }

    public boolean estConfigure() {
        return configure;
    }

    /**
     * Envoie un message. <b>Ne lève jamais.</b>
     *
     * <p>Quand le SMTP n'est pas configuré, le contenu part dans les journaux
     * en {@code WARN}. C'est délibéré : en développement, on veut pouvoir
     * suivre le lien de confirmation sans monter un serveur de messagerie.</p>
     *
     * <p>⚠️ Ce repli ne doit <b>jamais</b> servir en production — un lien de
     * confirmation dans un journal est un lien lisible par quiconque a accès
     * aux journaux. Le message d'avertissement au démarrage est là pour que
     * l'oubli se voie.</p>
     *
     * @return vrai si le message est parti
     */
    public boolean envoyer(String destinataire, String sujet, String corpsHtml) {
        if (!configure) {
            log.warn("""
                    E-MAIL NON ENVOYE (SMTP non configure)
                      destinataire : {}
                      sujet        : {}
                    {}""", destinataire, sujet, texteBrut(corpsHtml));
            return false;
        }

        try {
            JavaMailSender envoi = expediteur.getIfAvailable();
            if (envoi == null) {
                log.error("spring.mail.host est renseigne mais aucun JavaMailSender "
                        + "n'a ete cree : configuration incoherente.");
                return false;
            }

            MimeMessage message = envoi.createMimeMessage();
            MimeMessageHelper aide = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());

            aide.setFrom(adresseExpediteur, nomExpediteur);
            aide.setTo(destinataire);
            aide.setSubject(sujet);
            aide.setText(corpsHtml, true);

            envoi.send(message);
            log.info("E-mail envoye a {} : {}", masquer(destinataire), sujet);
            return true;

        } catch (MailException | java.io.UnsupportedEncodingException
                 | jakarta.mail.MessagingException e) {
            // ⚠️ On journalise le MESSAGE, pas seulement la classe — et c'est un
            // revirement.
            //
            // La première version ne gardait que le nom de l'exception, par
            // crainte d'y voir apparaître le mot de passe SMTP. Le premier
            // échec réel a montré l'erreur de raisonnement : le journal disait
            // « MailSendException » et rien d'autre, ce qui rendait tout
            // diagnostic impossible.
            //
            // Or JavaMail ne réémet PAS nos identifiants : son message porte la
            // RÉPONSE DU SERVEUR — « 535-5.7.8 Username and Password not
            // accepted », « 534 Application-specific password required ». C'est
            // précisément ce qu'il faut lire, et ce n'est pas un secret.
            //
            // 🎯 La leçon : un journal qui cache trop ne protège de rien, il
            //    empêche seulement de comprendre. Ce qu'il faut taire, ce sont
            //    NOS secrets — pas ce que le serveur d'en face nous répond.
            log.error("Envoi impossible a {} ({}) : {} — {}",
                    masquer(destinataire), sujet, e.getClass().getSimpleName(),
                    racine(e));
            return false;
        }
    }

    /**
     * Le message de la cause la plus profonde.
     *
     * <p>Spring enveloppe l'exception JavaMail, qui enveloppe elle-même celle du
     * transport. Le message utile — la réponse du serveur SMTP — est tout au
     * fond ; celui du dessus se contente de dire « Mail server connection
     * failed », ce qui n'apprend rien.</p>
     *
     * <p>Même raisonnement que {@code nomDeContrainte} dans le gestionnaire
     * d'erreurs global, qui remonte la chaîne pour trouver le nom de la
     * contrainte PostgreSQL.</p>
     */
    private static String racine(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.strip();
    }

    /**
     * Masque l'adresse dans les journaux.
     *
     * <p>{@code paul.durand@exemple.cm} devient {@code p***@exemple.cm}. Un
     * journal se copie, se transmet au support, et finit dans un fichier que
     * personne ne surveille — y laisser les adresses de tous les clients est
     * une fuite de données personnelles, lente mais certaine.</p>
     */
    private static String masquer(String adresse) {
        if (adresse == null || !adresse.contains("@")) {
            return "?";
        }
        int arobase = adresse.indexOf('@');
        return adresse.charAt(0) + "***" + adresse.substring(arobase);
    }

    /** Rend le HTML lisible dans un journal, sans dépendance externe. */
    private static String texteBrut(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^ +| +$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
