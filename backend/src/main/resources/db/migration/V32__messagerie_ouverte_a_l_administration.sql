-- =============================================================================
-- La messagerie interne s'ouvre à TOUS les comptes internes
-- =============================================================================
-- 🎯 CE QUI ETAIT FAUX
--
--    V30 faisait pointer les cinq clés étrangères de la messagerie vers
--    `responsable`. Un ADMIN ou un SUPER_ADMIN n'a pas de ligne dans cette
--    table : ils étaient donc EXCLUS de la messagerie interne, sans que
--    personne ne l'ait décidé — c'était une conséquence du schéma, pas une
--    règle métier.
--
--    L'API le disait même à l'écran : « un compte d'administration n'y a pas
--    de place ». C'était une contrainte technique déguisée en principe.
--
--    Or l'administration fait partie de la maison. Elle a autant de raisons
--    d'écrire à un chef de service qu'un chef de service en a de lui répondre.
--
-- -----------------------------------------------------------------------------
-- ⚠️ AUCUNE DONNEE NE BOUGE
-- -----------------------------------------------------------------------------
-- `responsable.id` est une clé primaire qui RÉFÉRENCE `utilisateur (id)` :
-- les deux tables partagent la même valeur. Repointer les clés vers
-- `utilisateur` élargit donc ce qui est accepté sans invalider une seule
-- ligne existante.
--
-- -----------------------------------------------------------------------------
-- ⚠️ LES COLONNES SONT RENOMMÉES, ET CE N'EST PAS COSMÉTIQUE
-- -----------------------------------------------------------------------------
-- `responsable_a` pointant vers `utilisateur` serait un nom qui ment. Le
-- prochain à lire ce schéma en conclurait que la messagerie est réservée aux
-- responsables — c'est-à-dire exactement l'erreur qu'on corrige ici.
--
-- `expediteur_id`, `bloqueur_id` et `bloque_id` ne nomment pas de type : ils
-- restent tels quels.
--
-- -----------------------------------------------------------------------------
-- ⚠️ CE QUI RESTE INTERDIT
-- -----------------------------------------------------------------------------
-- Un CLIENT n'a rien à faire ici : c'est une messagerie INTERNE. La base ne
-- sait pas l'exprimer — `utilisateur` contient les quatre types — donc c'est
-- `ServiceMessagerie` qui le vérifie, et un test qui le tient.
-- =============================================================================


-- --- fil_interne -------------------------------------------------------------

ALTER TABLE fil_interne RENAME COLUMN responsable_a TO utilisateur_a;
ALTER TABLE fil_interne RENAME COLUMN responsable_b TO utilisateur_b;

ALTER TABLE fil_interne DROP CONSTRAINT fil_interne_responsable_a_fkey;
ALTER TABLE fil_interne DROP CONSTRAINT fil_interne_responsable_b_fkey;

ALTER TABLE fil_interne
    ADD CONSTRAINT fil_interne_utilisateur_a_fkey
    FOREIGN KEY (utilisateur_a) REFERENCES utilisateur (id) ON DELETE CASCADE;

ALTER TABLE fil_interne
    ADD CONSTRAINT fil_interne_utilisateur_b_fkey
    FOREIGN KEY (utilisateur_b) REFERENCES utilisateur (id) ON DELETE CASCADE;


-- --- message_interne ---------------------------------------------------------

ALTER TABLE message_interne DROP CONSTRAINT message_interne_expediteur_id_fkey;

ALTER TABLE message_interne
    ADD CONSTRAINT message_interne_expediteur_id_fkey
    FOREIGN KEY (expediteur_id) REFERENCES utilisateur (id);


-- --- blocage_message ---------------------------------------------------------

ALTER TABLE blocage_message DROP CONSTRAINT blocage_message_bloqueur_id_fkey;
ALTER TABLE blocage_message DROP CONSTRAINT blocage_message_bloque_id_fkey;

ALTER TABLE blocage_message
    ADD CONSTRAINT blocage_message_bloqueur_id_fkey
    FOREIGN KEY (bloqueur_id) REFERENCES utilisateur (id) ON DELETE CASCADE;

ALTER TABLE blocage_message
    ADD CONSTRAINT blocage_message_bloque_id_fkey
    FOREIGN KEY (bloque_id) REFERENCES utilisateur (id) ON DELETE CASCADE;
