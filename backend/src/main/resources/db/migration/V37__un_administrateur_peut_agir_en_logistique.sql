-- =============================================================================
-- Un administrateur peut enregistrer une étape, remettre une commande et
-- prendre une réclamation
-- =============================================================================
-- 🎯 LE DEFAUT (D-50)
--
--    Enregistrer le depart d'un colis, depuis un compte ADMIN, echouait avec
--    « Cette operation renvoie a un element qui n'existe pas, ou qui a ete
--    supprime entre-temps ». Rien n'avait ete supprime : trois colonnes qui
--    notent QUI a agi referencaient `responsable`, et un ADMIN ou un
--    SUPER_ADMIN n'a pas de ligne dans cette table.
--
--    C'est le meme defaut que la messagerie interne (V32) et les
--    conversations (V33), a trois autres endroits — et de nouveau, personne
--    ne l'avait decide : une contrainte de schema passait pour une regle.
--
-- -----------------------------------------------------------------------------
-- ⚠️ AUCUNE DONNEE NE BOUGE
-- -----------------------------------------------------------------------------
-- `responsable.id` est une cle primaire qui REFERENCE `utilisateur (id)` : les
-- deux tables portent la meme valeur. Repointer elargit ce qui est accepte
-- sans invalider une ligne existante.
--
-- ⚠️ Les colonnes gardent leur nom. Les renommer toucherait les vues, le suivi
--    public et le comptoir pour un gain de lecture seulement ; le commentaire
--    dit desormais ce qu'elles contiennent.
-- =============================================================================


-- --- Qui a enregistre l'etape d'un colis -------------------------------------

ALTER TABLE evenement_expedition
    DROP CONSTRAINT IF EXISTS evenement_expedition_responsable_id_fkey;
ALTER TABLE evenement_expedition
    ADD CONSTRAINT evenement_expedition_auteur_fkey
    FOREIGN KEY (responsable_id) REFERENCES utilisateur (id);

COMMENT ON COLUMN evenement_expedition.responsable_id IS
    'Qui a enregistre l''etape : tout compte de l''equipe, administrateurs compris.';


-- --- Qui a remis la marchandise au comptoir ----------------------------------

ALTER TABLE retrait_marchandise
    DROP CONSTRAINT IF EXISTS retrait_marchandise_confirme_par_fkey;
ALTER TABLE retrait_marchandise
    ADD CONSTRAINT retrait_marchandise_confirme_par_fkey
    FOREIGN KEY (confirme_par) REFERENCES utilisateur (id);


-- --- Qui a pris la reclamation en charge -------------------------------------

ALTER TABLE reclamation
    DROP CONSTRAINT IF EXISTS reclamation_responsable_id_fkey;
ALTER TABLE reclamation
    ADD CONSTRAINT reclamation_pris_par_fkey
    FOREIGN KEY (responsable_id) REFERENCES utilisateur (id);

COMMENT ON COLUMN reclamation.responsable_id IS
    'Qui a pris la reclamation en charge : tout compte de l''equipe, administrateurs compris.';
