-- =============================================================================
-- V24 — Un motif de revocation pour le changement de mot de passe
-- =============================================================================
-- Changer son mot de passe doit couper TOUTES les sessions ouvertes. Sinon,
-- le faire apres un vol ne chasse pas le voleur : il garde son jeton de
-- rafraichissement, valable quatorze jours (D-19).
--
-- Le motif existant le plus proche etait COMPTE_FERME. L utiliser aurait
-- ecrit, dans un journal d audit qu on ne reecrit jamais, que le compte avait
-- ete ferme — alors que son proprietaire venait simplement de changer son mot
-- de passe. Six mois plus tard, personne ne saurait plus lequel des deux s est
-- reellement produit.
--
-- ⚠️ Le motif est verrouille par une contrainte CHECK (V21). Ajouter une
--    valeur a l enumeration Java SANS cette migration ne produit aucune erreur
--    de compilation : la panne arrive a l execution, au premier changement de
--    mot de passe, sous la forme d une violation de contrainte.
-- =============================================================================

ALTER TABLE jeton_rafraichissement
    DROP CONSTRAINT jeton_rafraichissement_motif_valide;

ALTER TABLE jeton_rafraichissement
    ADD CONSTRAINT jeton_rafraichissement_motif_valide
        CHECK (motif_revocation IS NULL OR motif_revocation IN
               ('ROTATION', 'DECONNEXION', 'REUTILISATION', 'COMPTE_FERME',
                'MOT_DE_PASSE_CHANGE'));
