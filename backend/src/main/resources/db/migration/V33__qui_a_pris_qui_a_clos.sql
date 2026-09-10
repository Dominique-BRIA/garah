-- =============================================================================
-- Qui a pris la conversation, et qui l'a close
-- =============================================================================
-- 🎯 DEUX MANQUES, ET UN TROISIEME QU'ON NE VOYAIT PAS
--
--    1. `date_cloture` disait QUAND. Rien ne disait QUI. Sur un litige, savoir
--       qu'une conversation a été close le 3 mars à 14h12 sans savoir par qui
--       ne sert à rien — c'est précisément la question qu'on pose.
--
--    2. `responsable_id` REFERENCE `responsable`. Un ADMIN ou un SUPER_ADMIN
--       n'a pas de ligne dans cette table : ils ne pouvaient donc PAS prendre
--       une conversation. Le même défaut qu'en messagerie interne (V32), à un
--       autre endroit — et de nouveau, personne ne l'avait décidé.
--
--    3. Le nom `responsable_id` lui-même. Il annonce un type d'acteur là où
--       quatre sont possibles. C'est ce genre de nom qui a fait passer une
--       contrainte de schéma pour une règle métier.
--
-- -----------------------------------------------------------------------------
-- ⚠️ AUCUNE DONNEE NE BOUGE
-- -----------------------------------------------------------------------------
-- `responsable.id` est une clé primaire qui REFERENCE `utilisateur (id)` : les
-- deux tables portent la même valeur. Repointer élargit ce qui est accepté
-- sans invalider une ligne existante.
-- =============================================================================


-- --- conversation : qui a pris -----------------------------------------------

ALTER TABLE conversation RENAME COLUMN responsable_id TO pris_par;

ALTER TABLE conversation DROP CONSTRAINT conversation_responsable_id_fkey;
ALTER TABLE conversation
    ADD CONSTRAINT conversation_pris_par_fkey
    FOREIGN KEY (pris_par) REFERENCES utilisateur (id);

-- I-29, reformulé : WAITING ⇔ personne ne l'a prise.
ALTER TABLE conversation DROP CONSTRAINT conversation_responsable_coherent;
ALTER TABLE conversation
    ADD CONSTRAINT conversation_prise_coherente CHECK (
        (statut = 'WAITING'  AND pris_par IS NULL)
     OR (statut IN ('ASSIGNED', 'CLOSED') AND pris_par IS NOT NULL)
    );


-- --- conversation : qui a clos -----------------------------------------------

ALTER TABLE conversation ADD COLUMN clos_par bigint REFERENCES utilisateur (id);

-- ⚠️ On NE REMPLIT PAS retroactivement. Une conversation close avant cette
--    migration a été close par quelqu'un que la base n'a jamais su nommer :
--    y écrire `pris_par` serait une supposition, et une supposition dans un
--    journal qu'on relit sur litige est pire qu'une case vide.
COMMENT ON COLUMN conversation.clos_par IS
    'Qui a clos. NULL pour les conversations closes avant V33 : inconnu, pas supposé.';

-- ⚠️ Le lien tient dans les DEUX sens : clos_par renseigné ⇔ statut CLOSED.
--    Sans lui, on pourrait nommer celui qui a clos une conversation ouverte.
ALTER TABLE conversation
    ADD CONSTRAINT conversation_cloture_coherente CHECK (
        clos_par IS NULL OR statut = 'CLOSED'
    );


-- --- affectation_conversation ------------------------------------------------
-- L'historique des prises. Même raison : un administrateur peut désormais y
-- figurer.

ALTER TABLE affectation_conversation RENAME COLUMN responsable_id TO pris_par;

ALTER TABLE affectation_conversation DROP CONSTRAINT affectation_conversation_responsable_id_fkey;
ALTER TABLE affectation_conversation
    ADD CONSTRAINT affectation_conversation_pris_par_fkey
    FOREIGN KEY (pris_par) REFERENCES utilisateur (id);
