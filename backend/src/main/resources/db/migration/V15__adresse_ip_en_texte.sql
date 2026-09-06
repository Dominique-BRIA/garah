-- =============================================================================
-- V15 — Les adresses IP passent de inet à varchar(45)
-- =============================================================================
-- POURQUOI ce changement, alors que `inet` est le type "juste" ?
--
-- PostgreSQL ne convertit PAS implicitement varchar → inet lors d'un INSERT.
-- Hibernate envoie une chaîne, et la base refuse :
--
--     ERREUR : la colonne « adresse_ip » est de type inet
--              mais l'expression est de type character varying
--
-- Trois issues étaient possibles :
--
--   1. mapper le champ Java en java.net.InetAddress  → conversion à chaque
--      lecture et écriture, et une exception possible sur une valeur mal
--      formée reçue d'un en-tête HTTP que l'on ne contrôle pas ;
--   2. écrire un convertisseur Hibernate dédié       → du code d'infrastructure
--      à maintenir pour quatre colonnes ;
--   3. stocker du texte                              → retenu.
--
-- CE QU'ON PERD : les opérateurs réseau de PostgreSQL (`<<=` pour « appartient
-- à ce sous-réseau », tri correct des adresses). Le jour où le score de risque
-- voudra raisonner par sous-réseau, il faudra revenir en arrière — ce sera une
-- migration de plus, sur des colonnes qui contiennent déjà des adresses
-- valides.
--
-- CE QU'ON GAGNE : aucune friction entre l'ORM et la base sur une donnée
-- qu'on ne fait, aujourd'hui, qu'écrire et relire telle quelle.
--
-- ⚠️ Illustration directe de la règle d'or de Flyway (chapitre 05) : on ne
--    retouche PAS V12, déjà appliquée. On ajoute une migration.
--    varchar(45) tient une adresse IPv6 complète.
-- =============================================================================

ALTER TABLE evenement_securite
    ALTER COLUMN adresse_ip TYPE varchar(45) USING host(adresse_ip);

ALTER TABLE activite_client
    ALTER COLUMN adresse_ip TYPE varchar(45) USING host(adresse_ip);

ALTER TABLE audit_log
    ALTER COLUMN adresse_ip TYPE varchar(45) USING host(adresse_ip);

ALTER TABLE vue_produit
    ALTER COLUMN adresse_ip TYPE varchar(45) USING host(adresse_ip);

COMMENT ON COLUMN evenement_securite.adresse_ip IS
    'Texte et non inet (V15). Renseignée depuis X-Forwarded-For derrière Render.';
