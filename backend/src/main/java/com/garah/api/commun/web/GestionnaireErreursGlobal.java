package com.garah.api.commun.web;

import com.garah.api.commun.erreur.ErreurMetier;
import com.garah.api.commun.erreur.ReponseErreur;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduit toute exception en une {@link ReponseErreur} unique.
 *
 * <p>Sans ce gestionnaire, chaque endpoint renverrait sa propre forme d'erreur,
 * et les trois frontends devraient toutes les connaître.</p>
 */
@RestControllerAdvice
public class GestionnaireErreursGlobal {

    private static final Logger log = LoggerFactory.getLogger(GestionnaireErreursGlobal.class);

    /**
     * Traduction des contraintes SQL en messages lisibles.
     *
     * <p>⚠️ Arriver ici signifie que le service <b>aurait dû</b> vérifier avant.
     * Le chapitre 04 le dit : « la base est la dernière ligne de défense, jamais
     * la seule ligne visible ». C'est pourquoi ces cas sont journalisés en
     * WARN — une contrainte qui se déclenche est un <b>bug de service</b>,
     * pas un fonctionnement normal.</p>
     */
    private static final Map<String, String> MESSAGES_CONTRAINTES = Map.ofEntries(
            Map.entry("stock_quantites_positives",
                    "Le stock demandé n'est pas disponible."),
            Map.entry("panier_actif_unique",
                    "Ce client a déjà un panier en cours."),
            Map.entry("responsable_categorie_principale_unique",
                    "Ce responsable a déjà une catégorie principale."),
            Map.entry("tarification_sans_chevauchement",
                    "Ce palier de quantité chevauche un palier existant."),
            Map.entry("commande_total_coherent",
                    "Le total de la commande ne correspond pas à ses lignes."),
            Map.entry("ligne_commande_montant_coherent",
                    "Le montant de la ligne ne correspond pas à la quantité et au prix."),
            Map.entry("ligne_commande_commission_coherente",
                    "Le montant de commission ne correspond pas au taux appliqué."),
            Map.entry("ligne_commande_tva_coherente",
                    "Le montant de TVA ne correspond pas au taux appliqué."),
            Map.entry("commande_point_recuperation_fk",
                    "Le lieu choisi n'est pas un point de récupération valide."),
            Map.entry("conversation_responsable_coherent",
                    "Une conversation assignée doit avoir un responsable."),
            Map.entry("utilisateur_email_unique",
                    "Cette adresse e-mail est déjà utilisée."),
            Map.entry("ecriture_marchand_signe_coherent",
                    "Le signe du montant ne correspond pas au type d'écriture."),
            Map.entry("gestion_marchand_active_unique",
                    "Ce marchand est déjà géré par un responsable."),
            Map.entry("affectation_conversation_ouverte_unique",
                    "Cette conversation est déjà affectée."));

    /** Erreurs métier explicites : elles portent déjà leur code et leur statut. */
    @ExceptionHandler(ErreurMetier.class)
    public ResponseEntity<ReponseErreur> erreurMetier(ErreurMetier e, HttpServletRequest requete) {
        return ResponseEntity
                .status(e.getStatut())
                .body(ReponseErreur.de(e.getCode(), e.getMessage(), requete.getRequestURI()));
    }

    /** Validation des DTO d'entrée : on renvoie le détail champ par champ. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ReponseErreur> validation(MethodArgumentNotValidException e,
                                                    HttpServletRequest requete) {
        Map<String, String> champs = new LinkedHashMap<>();
        for (FieldError erreur : e.getBindingResult().getFieldErrors()) {
            champs.putIfAbsent(erreur.getField(), erreur.getDefaultMessage());
        }
        return ResponseEntity
                .badRequest()
                .body(ReponseErreur.validation("Certains champs sont invalides.", champs,
                        requete.getRequestURI()));
    }

    /**
     * Une contrainte de la base a été violée.
     *
     * <p>On traduit le nom de la contrainte, et on journalise : le client ne doit
     * jamais voir « violates check constraint », qui est illisible et qui expose
     * la structure interne de la base.</p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ReponseErreur> integrite(DataIntegrityViolationException e,
                                                   HttpServletRequest requete) {
        String contrainte = nomDeContrainte(e);
        String message = MESSAGES_CONTRAINTES.get(contrainte);

        if (message != null) {
            log.warn("Contrainte '{}' declenchee sur {} : le service aurait du verifier avant.",
                    contrainte, requete.getRequestURI());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ReponseErreur.de("CONTRAINTE_" + contrainte.toUpperCase(),
                            message, requete.getRequestURI()));
        }

        // Contrainte inconnue : on ne devine pas, et on ne fuit rien.
        log.error("Violation d'integrite non traduite sur {}", requete.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ReponseErreur.de("CONFLIT_DONNEES",
                        "L'opération est en conflit avec des données existantes.",
                        requete.getRequestURI()));
    }

    /**
     * Erreurs de routage et de format gérées par Spring MVC lui-même.
     *
     * <p>⚠️ Sans ce bloc, le filet de sécurité {@code Exception.class} plus bas
     * les attraperait et transformerait un honnête {@code 404} en
     * {@code 500 ERREUR_INTERNE}. Un filet trop large ne se contente pas
     * d'attraper ce qu'on a oublié : il attrape aussi ce que le framework
     * gérait très bien.</p>
     */
    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ReponseErreur> requeteMalFormee(Exception e, HttpServletRequest requete) {
        HttpStatus statut;
        String code;
        String message;

        switch (e) {
            case NoResourceFoundException ignored -> {
                statut = HttpStatus.NOT_FOUND;
                code = "ROUTE_INTROUVABLE";
                message = "Cette adresse n'existe pas.";
            }
            case NoHandlerFoundException ignored -> {
                statut = HttpStatus.NOT_FOUND;
                code = "ROUTE_INTROUVABLE";
                message = "Cette adresse n'existe pas.";
            }
            case HttpRequestMethodNotSupportedException ignored -> {
                statut = HttpStatus.METHOD_NOT_ALLOWED;
                code = "METHODE_NON_AUTORISEE";
                message = "Cette méthode HTTP n'est pas acceptée sur cette adresse.";
            }
            case HttpMediaTypeNotSupportedException ignored -> {
                statut = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
                code = "FORMAT_NON_SUPPORTE";
                message = "Le format envoyé n'est pas accepté.";
            }
            default -> {
                statut = HttpStatus.BAD_REQUEST;
                code = "REQUETE_INVALIDE";
                message = "La requête est mal formée.";
            }
        }

        return ResponseEntity.status(statut)
                .body(ReponseErreur.de(code, message, requete.getRequestURI()));
    }

    /** Filet de sécurité : aucune trace technique ne doit sortir vers le client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ReponseErreur> inattendue(Exception e, HttpServletRequest requete) {
        log.error("Erreur inattendue sur {}", requete.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ReponseErreur.de("ERREUR_INTERNE",
                        "Une erreur interne est survenue.", requete.getRequestURI()));
    }

    /**
     * Remonte la chaîne des causes pour trouver le nom de la contrainte PostgreSQL.
     * Spring enveloppe l'exception Hibernate, qui enveloppe elle-même celle du pilote.
     */
    private String nomDeContrainte(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName() == null ? "" : violation.getConstraintName();
            }
            cause = cause.getCause();
        }
        return "";
    }
}
