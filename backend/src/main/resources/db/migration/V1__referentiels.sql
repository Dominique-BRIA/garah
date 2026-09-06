-- =============================================================================
-- V1 — Extensions et référentiels
-- =============================================================================
-- Cette migration pose les fondations sur lesquelles tout le reste s'appuie.
-- Elle doit rester la première : les extensions doivent exister avant les
-- contraintes qui les utilisent.
-- =============================================================================

-- btree_gist permet d'utiliser l'opérateur = (bigint) DANS une contrainte
-- d'exclusion GiST. Sans elle, la contrainte anti-chevauchement des paliers
-- de prix (V4) est impossible à créer.
CREATE EXTENSION IF NOT EXISTS btree_gist;


-- -----------------------------------------------------------------------------
-- LANGUE — le référentiel des langues d'interface (D-08, D-09)
-- -----------------------------------------------------------------------------
-- Trois lignes seulement, mais une vraie table : elle permet de désactiver une
-- langue sans redéployer l'application.
-- -----------------------------------------------------------------------------
CREATE TABLE langue (
    code        char(2)      PRIMARY KEY,
    libelle     varchar(50)  NOT NULL,
    actif       boolean      NOT NULL DEFAULT true,
    par_defaut  boolean      NOT NULL DEFAULT false,
    ordre       int          NOT NULL DEFAULT 0
);

COMMENT ON TABLE  langue IS 'Langues d''interface. Le contenu du catalogue n''est pas traduit (D-09).';
COMMENT ON COLUMN langue.par_defaut IS 'Langue de repli. Une seule ligne peut être à true.';

-- Une seule langue par défaut. Index unique PARTIEL : il ne porte que sur les
-- lignes où par_defaut est vrai, donc il n'interdit pas plusieurs "false".
CREATE UNIQUE INDEX langue_par_defaut_unique
    ON langue (par_defaut) WHERE par_defaut;

INSERT INTO langue (code, libelle, actif, par_defaut, ordre) VALUES
    ('fr', 'Français', true,  true,  1),
    ('en', 'English',  true,  false, 2),
    ('sg', 'Sängö',    true,  false, 3);
