-- =============================================================================
-- V31 — Le chef de service
-- =============================================================================
-- 🎯 LA HIÉRARCHIE COMPLÈTE, ET CE QUI EXISTAIT DÉJÀ
--
--     Super Admin      TypeUtilisateur.SUPER_ADMIN   ✅ existe (V2)
--     Admin            TypeUtilisateur.ADMIN         ✅ existe (V2)
--     Chef de service  ← CETTE MIGRATION
--     Membre           un responsable ordinaire      ✅ existe (V2)
--
-- Les deux premiers rangs étaient déjà là, portés par `utilisateur.type`. Il
-- ne manquait que le troisième — et surtout, la PORTÉE : un chef n'agit que
-- sur SON service.
--
-- ⚠️ LE PROFIL EST LE SERVICE.
--
--    « Logistique » est à la fois le profil qui donne des droits et le service
--    dont on est membre. Créer une seconde table `service` aurait donné deux
--    regroupements à tenir à jour — et le jour où ils divergent, on a un
--    membre d'un service qui n'a pas les droits du profil correspondant, sans
--    que rien ne le signale.
--
--    Conséquence assumée : quelqu'un qui cumule deux profils appartient à deux
--    services, et peut être chef de l'un sans l'être de l'autre.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- QUI DIRIGE
-- -----------------------------------------------------------------------------
ALTER TABLE responsable_categorie
    ADD COLUMN chef boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN responsable_categorie.chef IS
    'Ce responsable dirige ce service. Un seul chef par service, et il en est '
    'necessairement membre — c''est la meme ligne qui porte les deux.';

-- ⚠️ UN SEUL CHEF PAR SERVICE.
--
--    Le meme procede que `principale` juste au-dessus : un index unique
--    PARTIEL n'interdit que le second `true`. Sans lui, deux chefs pourraient
--    se desactiver l'un l'autre — et on ne saurait pas lequel a raison.
--
--    Le « au moins un » n'est PAS exprime : un service sans chef est un etat
--    normal, celui d'avant la nomination.
CREATE UNIQUE INDEX responsable_categorie_chef_unique
    ON responsable_categorie (categorie_id) WHERE chef;

-- « Quels services est-ce que je dirige ? » est pose a chaque geste d'un chef.
CREATE INDEX responsable_categorie_chef_idx
    ON responsable_categorie (responsable_id) WHERE chef;


-- -----------------------------------------------------------------------------
-- LES NOUVELLES ACTIONS
-- -----------------------------------------------------------------------------
-- ⚠️ DES PERMISSIONS DISTINCTES, ET NON LES RESPONSABLE_* EXISTANTES BORNEES.
--
--    On aurait pu donner RESPONSABLE_DESACTIVER a un chef et limiter son effet
--    a son service. Ce serait un piege : la meme permission voudrait dire deux
--    choses differentes selon qui la detient, et l'ecran des profils ne
--    pourrait plus l'expliquer. « Peut desactiver un responsable » — lequel ?
--
--    Deux permissions, deux phrases claires :
--      RESPONSABLE_DESACTIVER      n'importe quel responsable
--      SERVICE_MEMBRE_DESACTIVER   ceux de mon service, et eux seuls
--
--    C'est un peu plus de lignes, et beaucoup moins d'ambiguite le jour ou
--    quelqu'un relit la liste des droits d'un profil.
--
-- Aucune n'est accordee ici : une migration qui distribue des droits les
-- redonnerait a chaque environnement, y compris ceux ou on venait de les
-- retirer. C'est un Admin qui les pose, profil par profil.
-- -----------------------------------------------------------------------------
INSERT INTO cas_utilisation (code, nom, description, module) VALUES

-- Ce que fait l'ADMIN : nommer.
('SERVICE_CHEF_NOMMER',            'Nommer un chef de service',
 'Designer le responsable qui dirige un service, ou le demettre.',      'ADMINISTRATION'),

-- Ce que fait le CHEF, sur son service et lui seul.
('SERVICE_MEMBRE_CONSULTER',       'Voir les membres de son service',
 'Lister les responsables du service que l''on dirige.',                'SERVICE'),
('SERVICE_MEMBRE_MODIFIER',        'Modifier un membre de son service',
 'Corriger les coordonnees d''un membre du service que l''on dirige.',  'SERVICE'),
('SERVICE_MEMBRE_DESACTIVER',      'Suspendre un membre de son service',
 'Suspendre un membre du service que l''on dirige.',                    'SERVICE'),
('SERVICE_MEMBRE_ACTIVER',         'Reactiver un membre de son service',
 'Reactiver un membre suspendu du service que l''on dirige.',           'SERVICE'),
('SERVICE_MEMBRE_MOT_DE_PASSE',    'Reinitialiser le mot de passe d''un membre',
 'Poser un nouveau mot de passe pour un membre de son service.',        'SERVICE'),
('SERVICE_MEMBRE_ACTIVITE',        'Voir l''activite d''un membre',
 'Consulter le journal des actions d''un membre de son service.',       'SERVICE');
