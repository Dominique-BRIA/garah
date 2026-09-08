-- =============================================================================
-- L'auteur d'un mouvement de stock est un UTILISATEUR, pas un responsable
-- =============================================================================
-- 🎯 UN NOM QUI MENT FINIT PAR ÊTRE CRU.
--
-- La colonne s'appelait `responsable_id` et référençait la table
-- `responsable`. Elle ne veut pourtant dire qu'une chose : QUI a enregistré ce
-- mouvement. Le nom a été lu comme un type, et la clé étrangère posée en
-- conséquence.
--
-- Or un ADMIN et un SUPER_ADMIN n'ont AUCUNE ligne dans `responsable` : ils
-- agissent sur le système, ils n'occupent pas un poste (chapitre 01 §3.2). Un
-- administrateur qui saisissait une réception déclenchait donc une violation
-- de clé étrangère, rendue à l'écran par « L'opération est en conflit avec des
-- données existantes » — un message qui ne désigne rien.
--
-- ⚠️ Aucun test ne pouvait le voir : tous enregistraient leurs mouvements avec
--    un auteur NULL. La colonne était nullable, la contrainte ne se réveillait
--    jamais.
--
-- Le renommage n'est pas cosmétique : garder `responsable_id` en le faisant
-- pointer vers `utilisateur` laisserait le piège intact pour le prochain qui
-- lira le schéma.
-- =============================================================================

ALTER TABLE mouvement_stock
    DROP CONSTRAINT IF EXISTS mouvement_stock_responsable_id_fkey;

ALTER TABLE mouvement_stock
    RENAME COLUMN responsable_id TO auteur_id;

ALTER TABLE mouvement_stock
    ADD CONSTRAINT mouvement_stock_auteur_fkey
        FOREIGN KEY (auteur_id) REFERENCES utilisateur (id);

COMMENT ON COLUMN mouvement_stock.auteur_id IS
    'Qui a enregistre ce mouvement. NULL quand il vient du systeme : une '
    'reservation posee par une commande n''a pas d''auteur humain.';
