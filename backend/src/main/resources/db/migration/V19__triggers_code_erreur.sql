-- =============================================================================
-- V19 — Les triggers levent une vraie violation d integrite
-- =============================================================================
-- Defaut trouve en ecrivant le test de bout en bout du chapitre 15.
--
-- Un RAISE EXCEPTION en PL/pgSQL utilise par defaut le SQLSTATE P0001
-- (raise_exception). Ce code n appartient PAS a la classe 23 des violations
-- d integrite. Consequence en cascade :
--
--   PostgreSQL   P0001
--        ↓
--   Spring       JpaSystemException     et non DataIntegrityViolationException
--        ↓
--   API          500 ERREUR_INTERNE     et non 409 CONFLIT
--
-- Autrement dit : nos deux invariants les plus couteux (I-35 et I-40, ceux qui
-- protegent la marchandise et l argent) produisaient une erreur serveur
-- illisible au lieu d un conflit metier comprehensible.
--
-- LA CORRECTION : USING ERRCODE = '23514' (check_violation). Le message ne
-- change pas ; sa CLASSIFICATION oui. Spring le traduit alors en
-- DataIntegrityViolationException, et le gestionnaire global du chapitre 06
-- repond 409 comme pour n importe quelle contrainte.
--
-- ⚠️ La lecon depasse ce cas : un trigger ne fait pas que verifier une regle,
--    il doit aussi se PRESENTER correctement a la couche au-dessus. Une regle
--    juste mal classee est une regle qui casse l interface.
-- =============================================================================

CREATE OR REPLACE FUNCTION verifier_quantite_colis() RETURNS trigger AS $$
DECLARE
    qte_commandee int;
    qte_deja      int;
BEGIN
    SELECT quantite INTO qte_commandee
      FROM ligne_commande WHERE id = NEW.ligne_commande_id;

    SELECT COALESCE(SUM(quantite), 0) INTO qte_deja
      FROM ligne_colis
     WHERE ligne_commande_id = NEW.ligne_commande_id
       AND id IS DISTINCT FROM NEW.id;

    IF qte_deja + NEW.quantite > qte_commandee THEN
        RAISE EXCEPTION
            'I-35 : quantite en colis (%) superieure a la quantite commandee (%) pour la ligne_commande %',
            qte_deja + NEW.quantite, qte_commandee, NEW.ligne_commande_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION verifier_quantite_retour() RETURNS trigger AS $$
DECLARE
    qte_commandee int;
    qte_deja      int;
BEGIN
    SELECT quantite INTO qte_commandee
      FROM ligne_commande WHERE id = NEW.ligne_commande_id;

    SELECT COALESCE(SUM(lr.quantite), 0) INTO qte_deja
      FROM ligne_retour lr
      JOIN retour r ON r.id = lr.retour_id
     WHERE lr.ligne_commande_id = NEW.ligne_commande_id
       AND lr.id IS DISTINCT FROM NEW.id
       AND r.statut <> 'REFUSE';

    IF qte_deja + NEW.quantite > qte_commandee THEN
        RAISE EXCEPTION
            'I-40 : cumul retourne (%) superieur a la quantite commandee (%) pour la ligne_commande %',
            qte_deja + NEW.quantite, qte_commandee, NEW.ligne_commande_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
