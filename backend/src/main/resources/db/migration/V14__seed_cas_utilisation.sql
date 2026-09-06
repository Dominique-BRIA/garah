-- =============================================================================
-- V14 — Le catalogue des cas d'utilisation
-- =============================================================================
-- ⚠️ Ces lignes ne sont PAS des données de test : c'est un RÉFÉRENTIEL.
-- Le code Java s'appuie dessus, et l'interface les traduit via la clé
-- perm.<CODE> dans fr.json / en.json / sg.json (D-09).
--
-- Règles absolues :
--   • le CODE est immuable — le renommer casse le code Java ET les traductions ;
--   • un nouveau cas d'utilisation arrive par une NOUVELLE migration, jamais
--     en modifiant celle-ci : Flyway refuserait un fichier déjà appliqué ;
--   • un cas d'utilisation qui disparaît passe à statut INACTIF, il n'est
--     jamais supprimé (des exceptions individuelles y font référence).
-- =============================================================================

INSERT INTO cas_utilisation (code, nom, description, module) VALUES

-- --- Tableau de bord -------------------------------------------------------
('RESPONSABLE_CONSULTER_TABLEAU_BORD',        'Consulter le tableau de bord',        'Accéder à la page d''accueil du back-office.',                  'TABLEAU_BORD'),
('RESPONSABLE_CONSULTER_STATISTIQUES_AUTORISEES', 'Consulter ses statistiques',      'Voir les statistiques correspondant à ses propres droits.',     'TABLEAU_BORD'),
('RESPONSABLE_CONSULTER_NOTIFICATIONS',       'Consulter ses notifications',         'Voir la liste de ses notifications.',                           'TABLEAU_BORD'),
('RESPONSABLE_CONSULTER_ACTIVITE',            'Consulter son activité',              'Voir l''historique de ses propres actions.',                    'TABLEAU_BORD'),

-- --- Marchands -------------------------------------------------------------
('MARCHAND_CREER',                  'Créer un marchand',                'Enregistrer un nouveau marchand, interne ou externe.',      'MARCHAND'),
('MARCHAND_CONSULTER',              'Consulter les marchands',          'Voir la liste et la fiche des marchands.',                  'MARCHAND'),
('MARCHAND_MODIFIER',               'Modifier un marchand',             'Modifier les informations d''un marchand.',                 'MARCHAND'),
('MARCHAND_ACTIVER',                'Activer un marchand',              'Rendre un marchand actif.',                                 'MARCHAND'),
('MARCHAND_DESACTIVER',             'Désactiver un marchand',           'Suspendre l''activité d''un marchand.',                     'MARCHAND'),
('MARCHAND_AFFECTER',               'Affecter un marchand',             'Confier la gestion d''un marchand à un responsable.',        'MARCHAND'),
('MARCHAND_RETIRER_AFFECTATION',    'Retirer une affectation',          'Retirer un marchand à un responsable.',                     'MARCHAND'),
('MARCHAND_CONSULTER_HISTORIQUE',   'Historique d''un marchand',        'Voir l''historique des opérations d''un marchand.',          'MARCHAND'),
('MARCHAND_CONSULTER_STATISTIQUES', 'Statistiques d''un marchand',      'Voir les statistiques de vente d''un marchand.',             'MARCHAND'),

-- --- Produits --------------------------------------------------------------
('PRODUIT_CREER',                   'Créer un produit',                 'Créer une fiche produit.',                                  'PRODUIT'),
('PRODUIT_CONSULTER',               'Consulter les produits',           'Voir la liste et la fiche des produits.',                    'PRODUIT'),
('PRODUIT_MODIFIER',                'Modifier un produit',              'Modifier une fiche produit.',                                'PRODUIT'),
('PRODUIT_SUPPRIMER',               'Supprimer un produit',             'Supprimer un produit jamais vendu.',                         'PRODUIT'),
('PRODUIT_PUBLIER',                 'Publier un produit',               'Rendre un produit visible sur la plateforme.',               'PRODUIT'),
('PRODUIT_DEPUBLIER',               'Dépublier un produit',             'Retirer un produit de la vente.',                            'PRODUIT'),
('PRODUIT_MASQUER',                 'Masquer un produit',               'Masquer temporairement un produit.',                         'PRODUIT'),
('PRODUIT_ARCHIVER',                'Archiver un produit',              'Archiver définitivement un produit.',                        'PRODUIT'),
('PRODUIT_AJOUTER_PHOTO',           'Ajouter une photo',                'Ajouter une photo à un produit.',                            'PRODUIT'),
('PRODUIT_MODIFIER_PHOTO',          'Modifier une photo',               'Modifier l''ordre ou la photo principale.',                  'PRODUIT'),
('PRODUIT_SUPPRIMER_PHOTO',         'Supprimer une photo',              'Retirer une photo d''un produit.',                           'PRODUIT'),
('PRODUIT_AJOUTER_VIDEO',           'Ajouter une vidéo',                'Ajouter une vidéo à un produit.',                            'PRODUIT'),
('PRODUIT_MODIFIER_VIDEO',          'Modifier une vidéo',               'Modifier une vidéo d''un produit.',                          'PRODUIT'),
('PRODUIT_SUPPRIMER_VIDEO',         'Supprimer une vidéo',              'Retirer une vidéo d''un produit.',                           'PRODUIT'),
('PRODUIT_MODIFIER_DESCRIPTION',    'Modifier la description',          'Modifier la description d''un produit.',                     'PRODUIT'),
('PRODUIT_MODIFIER_CARACTERISTIQUES','Modifier les caractéristiques',   'Modifier les caractéristiques techniques.',                  'PRODUIT'),
('PRODUIT_ASSOCIER_MARCHAND',       'Associer un marchand',             'Rattacher un produit à un marchand.',                        'PRODUIT'),
('PRODUIT_MODIFIER_MARCHAND',       'Changer le marchand',              'Transférer un produit à un autre marchand.',                 'PRODUIT'),
('PRODUIT_CONSULTER_HISTORIQUE',    'Historique d''un produit',         'Voir l''historique des modifications d''un produit.',         'PRODUIT'),

-- --- Variantes (décision D-01) ---------------------------------------------
('VARIANTE_CREER',                  'Créer une variante',               'Ajouter une déclinaison à un produit.',                      'PRODUIT'),
('VARIANTE_CONSULTER',              'Consulter les variantes',          'Voir les déclinaisons d''un produit.',                       'PRODUIT'),
('VARIANTE_MODIFIER',               'Modifier une variante',            'Modifier une déclinaison.',                                  'PRODUIT'),
('VARIANTE_ACTIVER',                'Activer une variante',             'Remettre une déclinaison en vente.',                         'PRODUIT'),
('VARIANTE_DESACTIVER',             'Désactiver une variante',          'Retirer une déclinaison de la vente.',                       'PRODUIT'),
('ATTRIBUT_GERER',                  'Gérer les attributs',              'Gérer la grille de déclinaison (taille, couleur…).',          'PRODUIT'),
('CATEGORIE_PRODUIT_GERER',         'Gérer les catégories produit',     'Créer et organiser l''arborescence des catégories.',          'PRODUIT'),

-- --- Prix ------------------------------------------------------------------
('PRIX_CONSULTER',                     'Consulter les prix',            'Voir les tarifs d''une variante.',                           'PRIX'),
('PRIX_CREER',                         'Créer un prix',                 'Définir un tarif.',                                          'PRIX'),
('PRIX_MODIFIER',                      'Modifier un prix',              'Modifier un tarif existant.',                                'PRIX'),
('PRIX_SUPPRIMER',                     'Supprimer un prix',             'Supprimer un tarif.',                                        'PRIX'),
('TARIFICATION_QUANTITE_CREER',        'Créer un palier de quantité',   'Ajouter un palier de prix dégressif.',                       'PRIX'),
('TARIFICATION_QUANTITE_MODIFIER',     'Modifier un palier',            'Modifier un palier de prix dégressif.',                      'PRIX'),
('TARIFICATION_QUANTITE_SUPPRIMER',    'Supprimer un palier',           'Supprimer un palier de prix dégressif.',                     'PRIX'),

-- --- Stock -----------------------------------------------------------------
('STOCK_CONSULTER',              'Consulter le stock',           'Voir les quantités disponibles et réservées.',              'STOCK'),
('STOCK_AJUSTER',                'Ajuster le stock',             'Corriger une quantité après inventaire.',                    'STOCK'),
('STOCK_ENTREE_ENREGISTRER',     'Enregistrer une entrée',       'Enregistrer une réception de marchandise.',                  'STOCK'),
('STOCK_SORTIE_ENREGISTRER',     'Enregistrer une sortie',       'Enregistrer une sortie de marchandise.',                     'STOCK'),
('STOCK_RESERVER',               'Réserver du stock',            'Réserver manuellement des quantités.',                       'STOCK'),
('STOCK_LIBERER',                'Libérer une réservation',      'Libérer des quantités réservées.',                           'STOCK'),
('STOCK_CONSULTER_HISTORIQUE',   'Historique des mouvements',    'Voir l''historique des mouvements de stock.',                 'STOCK'),

-- --- Clients ---------------------------------------------------------------
('CLIENT_CONSULTER',                'Consulter les clients',        'Voir la liste et la fiche des clients.',                  'CLIENT'),
('CLIENT_MODIFIER',                 'Modifier un client',           'Modifier les informations d''un client.',                 'CLIENT'),
('CLIENT_ACTIVER',                  'Activer un client',            'Réactiver un compte client.',                              'CLIENT'),
('CLIENT_DESACTIVER',               'Désactiver un client',         'Suspendre un compte client.',                              'CLIENT'),
('CLIENT_CONSULTER_HISTORIQUE',     'Historique d''un client',      'Voir l''historique d''activité d''un client.',             'CLIENT'),
('CLIENT_CONSULTER_COMMANDES',      'Commandes d''un client',       'Voir les commandes d''un client.',                         'CLIENT'),
('CLIENT_CONSULTER_STATISTIQUES',   'Statistiques d''un client',    'Voir les statistiques d''achat d''un client.',             'CLIENT'),

-- --- Conversations ---------------------------------------------------------
('CONVERSATION_CONSULTER',              'Consulter les conversations',  'Voir les conversations.',                              'CONVERSATION'),
('CONVERSATION_PRENDRE',                'Prendre une conversation',     'S''attribuer une conversation en attente.',            'CONVERSATION'),
('CONVERSATION_REPONDRE',               'Répondre dans une conversation','Envoyer un message à un client.',                     'CONVERSATION'),
('CONVERSATION_FERMER',                 'Fermer une conversation',      'Clore une conversation traitée.',                       'CONVERSATION'),
('CONVERSATION_REAFFECTER',             'Réaffecter une conversation',  'Retirer une conversation à un responsable pour la donner à un autre.', 'CONVERSATION'),
('CONVERSATION_CONSULTER_HISTORIQUE',   'Historique des conversations', 'Voir l''historique des affectations.',                  'CONVERSATION'),
('CONVERSATION_CONSULTER_EVALUATION',   'Consulter les évaluations',    'Voir les notes laissées par les clients.',              'CONVERSATION'),

-- --- Négociation -----------------------------------------------------------
('NEGOCIATION_CONSULTER',               'Consulter les négociations',   'Voir les propositions de prix.',                        'NEGOCIATION'),
('NEGOCIATION_CREER_PROPOSITION',       'Créer une proposition',        'Proposer un prix à un client.',                         'NEGOCIATION'),
('NEGOCIATION_MODIFIER_PROPOSITION',    'Modifier une proposition',     'Modifier une proposition non encore acceptée.',         'NEGOCIATION'),
('NEGOCIATION_ACCEPTER',                'Accepter une proposition',     'Accepter la proposition d''un client.',                 'NEGOCIATION'),
('NEGOCIATION_REFUSER',                 'Refuser une proposition',      'Refuser la proposition d''un client.',                  'NEGOCIATION'),
('NEGOCIATION_CONSULTER_HISTORIQUE',    'Historique des négociations',  'Voir l''enchaînement des contre-propositions.',         'NEGOCIATION'),

-- --- Commandes -------------------------------------------------------------
('COMMANDE_CONSULTER',              'Consulter les commandes',      'Voir la liste des commandes.',                          'COMMANDE'),
('COMMANDE_CONSULTER_DETAILS',      'Détail d''une commande',       'Voir le détail des lignes d''une commande.',            'COMMANDE'),
('COMMANDE_CONFIRMER',              'Confirmer une commande',       'Valider une commande payée.',                            'COMMANDE'),
('COMMANDE_REFUSER',                'Refuser une commande',         'Refuser une commande.',                                  'COMMANDE'),
('COMMANDE_PREPARER',               'Préparer une commande',        'Lancer la préparation.',                                 'COMMANDE'),
('COMMANDE_MARQUER_PRETE',          'Marquer une commande prête',   'Déclarer la commande prête à expédier.',                 'COMMANDE'),
('COMMANDE_ANNULER',                'Annuler une commande',         'Annuler une commande, avec remboursement si elle est payée.', 'COMMANDE'),
('COMMANDE_CONSULTER_HISTORIQUE',   'Historique d''une commande',   'Voir l''historique des statuts d''une commande.',        'COMMANDE'),

-- --- Préparation -----------------------------------------------------------
('PREPARATION_CONSULTER',           'Consulter les préparations',   'Voir les commandes en préparation.',                     'PREPARATION'),
('PREPARATION_COMMENCER',           'Commencer une préparation',    'Démarrer la préparation d''une commande.',               'PREPARATION'),
('PREPARATION_TERMINER',            'Terminer une préparation',     'Clore la préparation d''une commande.',                  'PREPARATION'),
('PREPARATION_SIGNALER_PROBLEME',   'Signaler un problème',         'Signaler une anomalie pendant la préparation.',          'PREPARATION'),

-- --- Expédition ------------------------------------------------------------
('EXPEDITION_CREER',                'Créer une expédition',         'Créer une expédition pour une commande.',                'EXPEDITION'),
('EXPEDITION_CONSULTER',            'Consulter les expéditions',    'Voir les expéditions.',                                  'EXPEDITION'),
('EXPEDITION_MODIFIER',             'Modifier une expédition',      'Modifier une expédition non partie.',                    'EXPEDITION'),
('EXPEDITION_ANNULER',              'Annuler une expédition',       'Annuler une expédition.',                                'EXPEDITION'),
('EXPEDITION_PREPARER',             'Préparer une expédition',      'Constituer les colis.',                                  'EXPEDITION'),
('EXPEDITION_EXPEDIER',             'Expédier',                     'Enregistrer le départ de l''expédition.',                'EXPEDITION'),
('EXPEDITION_CONSULTER_HISTORIQUE', 'Historique d''une expédition', 'Voir les événements d''une expédition.',                 'EXPEDITION'),

-- --- Itinéraires -----------------------------------------------------------
('ITINERAIRE_CREER',                'Créer un itinéraire',          'Définir un trajet type.',                                'ITINERAIRE'),
('ITINERAIRE_CONSULTER',            'Consulter les itinéraires',    'Voir les trajets types.',                                'ITINERAIRE'),
('ITINERAIRE_MODIFIER',             'Modifier un itinéraire',       'Modifier un trajet type.',                               'ITINERAIRE'),
('ITINERAIRE_SUPPRIMER',            'Supprimer un itinéraire',      'Supprimer un trajet type inutilisé.',                    'ITINERAIRE'),
('ETAPE_ITINERAIRE_AJOUTER',        'Ajouter une étape',            'Ajouter un point de passage.',                           'ITINERAIRE'),
('ETAPE_ITINERAIRE_MODIFIER',       'Modifier une étape',           'Modifier un point de passage.',                          'ITINERAIRE'),
('ETAPE_ITINERAIRE_SUPPRIMER',      'Supprimer une étape',          'Retirer un point de passage.',                           'ITINERAIRE'),
('ETAPE_ITINERAIRE_REORDONNER',     'Réordonner les étapes',        'Changer l''ordre des points de passage.',                'ITINERAIRE'),

-- --- Transit ---------------------------------------------------------------
('POINT_TRANSIT_CREER',             'Créer un point de transit',    'Enregistrer un nouveau point de transit.',               'TRANSIT'),
('POINT_TRANSIT_CONSULTER',         'Consulter les points de transit','Voir les points de transit.',                          'TRANSIT'),
('POINT_TRANSIT_MODIFIER',          'Modifier un point de transit', 'Modifier un point de transit.',                          'TRANSIT'),
('POINT_TRANSIT_ACTIVER',           'Activer un point de transit',  'Rendre un point de transit opérationnel.',               'TRANSIT'),
('POINT_TRANSIT_DESACTIVER',        'Désactiver un point de transit','Suspendre un point de transit.',                        'TRANSIT'),
('MARCHANDISE_RECEPTIONNER',        'Réceptionner une marchandise', 'Enregistrer l''arrivée d''un colis.',                    'TRANSIT'),
('MARCHANDISE_CONTROLER',           'Contrôler une marchandise',    'Contrôler l''état d''un colis.',                         'TRANSIT'),
('MARCHANDISE_SIGNER_RECEPTION',    'Signer une réception',         'Valider formellement une réception.',                    'TRANSIT'),
('MARCHANDISE_DECLARER_ANOMALIE',   'Déclarer une anomalie',        'Signaler un problème sur un colis.',                     'TRANSIT'),
('MARCHANDISE_CONSULTER_HISTORIQUE','Historique d''une marchandise','Voir le parcours complet d''un colis.',                  'TRANSIT'),
('TRANSIT_EXPEDIER',                'Expédier depuis un transit',   'Faire repartir un colis d''un point de transit.',        'TRANSIT'),
('TRANSIT_CONSULTER',               'Consulter les transits',       'Voir les colis présents dans un point de transit.',      'TRANSIT'),
('TRANSIT_ENREGISTRER_DEPART',      'Enregistrer un départ',        'Enregistrer le départ d''un colis.',                     'TRANSIT'),
('TRANSIT_CONSULTER_HISTORIQUE',    'Historique d''un transit',     'Voir l''historique d''un point de transit.',             'TRANSIT'),

-- --- Incidents -------------------------------------------------------------
('INCIDENT_CREER',                  'Créer un incident',            'Déclarer un incident logistique.',                       'INCIDENT'),
('INCIDENT_CONSULTER',              'Consulter les incidents',      'Voir les incidents.',                                    'INCIDENT'),
('INCIDENT_MODIFIER',               'Modifier un incident',         'Modifier la description d''un incident.',                'INCIDENT'),
('INCIDENT_PRENDRE_EN_CHARGE',      'Prendre en charge un incident','S''attribuer un incident.',                              'INCIDENT'),
('INCIDENT_RESOUDRE',               'Résoudre un incident',         'Clore un incident résolu.',                              'INCIDENT'),
('INCIDENT_CLASSER',                'Classer un incident',          'Classer un incident sans suite.',                        'INCIDENT'),
('INCIDENT_CONSULTER_HISTORIQUE',   'Historique des incidents',     'Voir l''historique des incidents.',                      'INCIDENT'),

-- --- Récupération ----------------------------------------------------------
('POINT_RECUPERATION_CONSULTER',    'Consulter les points de récupération', 'Voir les points de retrait.',                    'RECUPERATION'),
('POINT_RECUPERATION_CREER',        'Créer un point de récupération',       'Enregistrer un nouveau point de retrait.',       'RECUPERATION'),
('POINT_RECUPERATION_MODIFIER',     'Modifier un point de récupération',    'Modifier un point de retrait et ses frais.',     'RECUPERATION'),
('POINT_RECUPERATION_ACTIVER',      'Activer un point de récupération',     'Rendre un point de retrait disponible.',         'RECUPERATION'),
('POINT_RECUPERATION_DESACTIVER',   'Désactiver un point de récupération',  'Retirer un point de retrait du choix client.',   'RECUPERATION'),
('RETRAIT_CONSULTER',               'Consulter les retraits',               'Voir les retraits en attente.',                  'RECUPERATION'),
('RETRAIT_CONFIRMER',               'Confirmer un retrait',                 'Valider la remise au client contre son code.',   'RECUPERATION'),
('RETRAIT_REFUSER',                 'Refuser un retrait',                   'Refuser une remise (code invalide, litige).',    'RECUPERATION'),

-- --- Réclamations ----------------------------------------------------------
('RECLAMATION_CONSULTER',           'Consulter les réclamations',       'Voir les réclamations.',                             'RECLAMATION'),
('RECLAMATION_PRENDRE_EN_CHARGE',   'Prendre en charge une réclamation','S''attribuer une réclamation.',                       'RECLAMATION'),
('RECLAMATION_MODIFIER',            'Modifier une réclamation',         'Compléter une réclamation.',                         'RECLAMATION'),
('RECLAMATION_RESOUDRE',            'Résoudre une réclamation',         'Apporter une solution à une réclamation.',           'RECLAMATION'),
('RECLAMATION_FERMER',              'Fermer une réclamation',           'Clore une réclamation.',                             'RECLAMATION'),
('RECLAMATION_CONSULTER_HISTORIQUE','Historique des réclamations',      'Voir l''historique des réclamations.',               'RECLAMATION'),

-- --- Retours ---------------------------------------------------------------
('RETOUR_CONSULTER',        'Consulter les retours',    'Voir les demandes de retour.',                           'RETOUR'),
('RETOUR_ACCEPTER',         'Accepter un retour',       'Accepter une demande de retour.',                        'RETOUR'),
('RETOUR_REFUSER',          'Refuser un retour',        'Refuser une demande de retour.',                         'RETOUR'),
('RETOUR_RECEPTIONNER',     'Réceptionner un retour',   'Enregistrer l''arrivée des articles retournés.',         'RETOUR'),
('RETOUR_VALIDER',          'Valider un retour',        'Valider après contrôle, ce qui déclenche le remboursement.', 'RETOUR'),
('RETOUR_CLOTURER',         'Clôturer un retour',       'Clore définitivement un retour.',                        'RETOUR'),

-- --- Paiements -------------------------------------------------------------
('PAIEMENT_CONSULTER',              'Consulter les paiements',  'Voir les paiements.',                                'PAIEMENT'),
('PAIEMENT_VERIFIER',               'Vérifier un paiement',     'Rapprocher un paiement de la référence opérateur.',   'PAIEMENT'),
('PAIEMENT_CONFIRMER',              'Confirmer un paiement',    'Confirmer manuellement un paiement.',                 'PAIEMENT'),
('PAIEMENT_REFUSER',                'Refuser un paiement',      'Marquer un paiement comme refusé.',                   'PAIEMENT'),
('PAIEMENT_REMBOURSER',             'Rembourser',               'Émettre un remboursement.',                           'PAIEMENT'),
('PAIEMENT_CONSULTER_HISTORIQUE',   'Historique des paiements', 'Voir l''historique des tentatives et confirmations.', 'PAIEMENT'),

-- --- Finance marchands -----------------------------------------------------
('DETTE_MARCHAND_CONSULTER',             'Consulter le solde marchand',   'Voir ce que l''entreprise doit à un marchand.',        'FINANCE'),
('VENTES_MARCHAND_CONSULTER',            'Consulter les ventes marchand', 'Voir les ventes réalisées pour un marchand.',          'FINANCE'),
('REGLEMENT_MARCHAND_CONSULTER',         'Consulter les règlements',      'Voir les règlements versés.',                          'FINANCE'),
('REGLEMENT_MARCHAND_CREER',             'Créer un règlement',            'Enregistrer un versement à un marchand.',              'FINANCE'),
('REGLEMENT_MARCHAND_MODIFIER',          'Modifier un règlement',         'Modifier un règlement non encore payé.',               'FINANCE'),
('REGLEMENT_MARCHAND_ANNULER',           'Annuler un règlement',          'Annuler un règlement.',                                'FINANCE'),
('HISTORIQUE_FINANCIER_MARCHAND_CONSULTER','Grand livre d''un marchand',  'Voir toutes les écritures qui composent le solde.',    'FINANCE'),
('REGLE_COMMISSION_GERER',               'Gérer les taux de commission',  'Définir les taux de commission et leur période.',       'FINANCE'),

-- --- Statistiques ----------------------------------------------------------
('STATISTIQUE_GENERALE_CONSULTER',          'Statistiques générales',       'Vue d''ensemble de l''activité.',              'STATISTIQUE'),
('STATISTIQUE_VENTE_CONSULTER',             'Statistiques de vente',        'Chiffre d''affaires et volumes.',              'STATISTIQUE'),
('STATISTIQUE_CLIENT_CONSULTER',            'Statistiques clients',         'Acquisition, fidélisation, panier moyen.',     'STATISTIQUE'),
('STATISTIQUE_MARCHAND_CONSULTER',          'Statistiques marchands',       'Performance des marchands.',                    'STATISTIQUE'),
('STATISTIQUE_PRODUIT_CONSULTER',           'Statistiques produits',        'Vues, favoris, ventes, retours.',               'STATISTIQUE'),
('STATISTIQUE_PAIEMENT_CONSULTER',          'Statistiques paiements',       'Taux de réussite par moyen de paiement.',       'STATISTIQUE'),
('STATISTIQUE_LOGISTIQUE_CONSULTER',        'Statistiques logistiques',     'Délais, retards, performance des itinéraires.', 'STATISTIQUE'),
('STATISTIQUE_SERVICE_CONSULTER',           'Statistiques de service',      'Satisfaction, délais de réponse.',              'STATISTIQUE'),
('STATISTIQUE_ACTIVITE_INTERNE_CONSULTER',  'Activité interne',             'Activité des responsables.',                     'STATISTIQUE'),
('STATISTIQUE_TENDANCE_CONSULTER',          'Produits tendance',            'Dynamique récente des ventes et des vues.',      'STATISTIQUE'),
('STATISTIQUE_RESPONSABLE_CONSULTER',       'Statistiques par responsable', 'Performance individuelle d''un responsable.',    'STATISTIQUE'),

-- --- Administration (Admin) ------------------------------------------------
('RESPONSABLE_CREER',               'Créer un responsable',         'Créer un compte responsable.',                        'ADMINISTRATION'),
('RESPONSABLE_CONSULTER',           'Consulter les responsables',   'Voir la liste des responsables.',                     'ADMINISTRATION'),
('RESPONSABLE_MODIFIER',            'Modifier un responsable',      'Modifier un compte responsable.',                     'ADMINISTRATION'),
('RESPONSABLE_ACTIVER',             'Activer un responsable',       'Réactiver un compte responsable.',                    'ADMINISTRATION'),
('RESPONSABLE_DESACTIVER',          'Désactiver un responsable',    'Suspendre un compte responsable.',                    'ADMINISTRATION'),
('CATEGORIE_RESPONSABLE_CREER',     'Créer une catégorie',          'Créer un profil de responsable.',                     'ADMINISTRATION'),
('CATEGORIE_RESPONSABLE_CONSULTER', 'Consulter les catégories',     'Voir les profils de responsable.',                    'ADMINISTRATION'),
('CATEGORIE_RESPONSABLE_MODIFIER',  'Modifier une catégorie',       'Modifier un profil et ses permissions.',              'ADMINISTRATION'),
('CATEGORIE_RESPONSABLE_SUPPRIMER', 'Supprimer une catégorie',      'Supprimer un profil inutilisé.',                      'ADMINISTRATION'),
('PERMISSION_ATTRIBUER',            'Attribuer une permission',     'Ajouter une exception individuelle à un responsable.', 'ADMINISTRATION'),
('PERMISSION_RETIRER',              'Retirer une permission',       'Retirer une permission à un responsable.',            'ADMINISTRATION'),

-- --- Sécurité et audit (SuperAdmin) ----------------------------------------
('ADMIN_CREER',                     'Créer un administrateur',      'Créer un compte administrateur.',                     'SECURITE'),
('ADMIN_CONSULTER',                 'Consulter les administrateurs','Voir la liste des administrateurs.',                  'SECURITE'),
('ADMIN_MODIFIER',                  'Modifier un administrateur',   'Modifier un compte administrateur.',                  'SECURITE'),
('ADMIN_ACTIVER',                   'Activer un administrateur',    'Réactiver un compte administrateur.',                 'SECURITE'),
('ADMIN_DESACTIVER',                'Désactiver un administrateur', 'Suspendre un compte administrateur.',                 'SECURITE'),
('CAS_UTILISATION_CONSULTER',       'Consulter le référentiel',     'Voir le catalogue des fonctionnalités.',              'SECURITE'),
('CAS_UTILISATION_MODIFIER',        'Modifier le référentiel',      'Modifier le libellé ou le statut d''un cas d''utilisation. Le code reste immuable.', 'SECURITE'),
('SURVEILLANCE_CONSULTER_ACTIVITE', 'Surveiller l''activité client','Consulter l''activité détaillée d''un client.',       'SECURITE'),
('SURVEILLANCE_CONSULTER_RISQUE',   'Consulter le score de risque', 'Voir le score de risque et ses signaux.',             'SECURITE'),
('SURVEILLANCE_TRAITER_ALERTE',     'Traiter une alerte',           'Décider de la suite à donner à une alerte.',          'SECURITE'),
('AUDIT_CONSULTER',                 'Consulter l''audit',           'Consulter le journal des actions internes.',          'SECURITE');
