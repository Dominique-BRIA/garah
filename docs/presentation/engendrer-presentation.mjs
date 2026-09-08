// =============================================================================
//  GARAH — la presentation aux partenaires
// =============================================================================
//  Chaque diapositive porte une NOTE : ce qu'il faut dire en la montrant.
//  Le public n'est pas technicien : le sujet est le commerce, jamais l'outil.
// =============================================================================
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import pptxgen from 'pptxgenjs';

const pres = new pptxgen();
pres.layout = 'LAYOUT_16x9';           // 10 x 5.625 pouces
pres.author = 'GARAH';
pres.title = 'GARAH — Vendre, servir et acheminer';

const W = 10, H = 5.625;

// --- La charte ---------------------------------------------------------------
const VERT = '12A594';       // la marque
const VERT_SOMBRE = '0B7A6D';
const ENCRE = '0F172A';
const GRIS = '64748B';
const GRIS_CLAIR = 'E2E8F0';
const FOND = 'F8FAFC';
const BLANC = 'FFFFFF';
const AMBRE = 'D97706';

const TITRE = { fontFace: 'Georgia', bold: true };
const TEXTE = { fontFace: 'Calibri' };

// -----------------------------------------------------------------------------
// Les gabarits
// -----------------------------------------------------------------------------

/** La couverture, et la diapositive de cloture. */
function couverture({ titre, sousTitre, pied, note }) {
  const s = pres.addSlide();
  s.background = { color: ENCRE };

  // Un aplat vert en biais : la seule fantaisie graphique du jeu, et elle
  // n'apparait que sur les deux diapositives qui encadrent la presentation.
  s.addShape(pres.ShapeType.rect, { x: 0, y: 0, w: 0.28, h: H, fill: { color: VERT } });

  s.addText(titre, {
    x: 0.9, y: 1.55, w: 8.5, h: 1.0, ...TITRE, fontSize: 54, color: BLANC, charSpacing: 2,
  });
  s.addText(sousTitre, {
    x: 0.9, y: 2.6, w: 8.2, h: 0.9, ...TEXTE, fontSize: 20, color: VERT, lineSpacingMultiple: 1.2,
  });
  if (pied) {
    s.addText(pied, { x: 0.9, y: 4.55, w: 8.2, h: 0.5, ...TEXTE, fontSize: 12, color: GRIS });
  }
  if (note) s.addNotes(note);
  return s;
}

/** L'ouverture d'une partie : un chiffre, un titre, une phrase. */
function partie({ numero, titre, phrase, note }) {
  const s = pres.addSlide();
  s.background = { color: ENCRE };

  s.addText(String(numero).padStart(2, '0'), {
    x: 0.9, y: 1.5, w: 2, h: 1.4, ...TITRE, fontSize: 96, color: VERT_SOMBRE,
  });
  s.addText(titre, {
    x: 0.9, y: 2.8, w: 8.2, h: 0.8, ...TITRE, fontSize: 40, color: BLANC,
  });
  s.addShape(pres.ShapeType.rect, { x: 0.95, y: 3.62, w: 1.2, h: 0.05, fill: { color: VERT } });
  if (phrase) {
    s.addText(phrase, {
      x: 0.9, y: 3.85, w: 7.8, h: 0.8, ...TEXTE, fontSize: 16, color: GRIS, lineSpacingMultiple: 1.3,
    });
  }
  if (note) s.addNotes(note);
  return s;
}

/** L'ossature commune d'une diapositive de contenu. */
function page(titre, chapeau) {
  const s = pres.addSlide();
  s.background = { color: FOND };

  s.addShape(pres.ShapeType.rect, { x: 0, y: 0, w: 0.14, h: H, fill: { color: VERT } });
  s.addText(titre, {
    x: 0.62, y: 0.42, w: 8.8, h: 0.6, ...TITRE, fontSize: 28, color: ENCRE,
  });

  let y = 1.15;
  if (chapeau) {
    s.addText(chapeau, {
      x: 0.62, y: 1.02, w: 8.8, h: 0.5, ...TEXTE, fontSize: 14, color: GRIS,
      italic: true, lineSpacingMultiple: 1.2,
    });
    y = 1.62;
  }
  return { s, y };
}

/** Des points, avec le premier segment en gras jusqu'au tiret. */
function puces({ titre, chapeau, items, note }) {
  const { s, y } = page(titre, chapeau);

  const lignes = items.map((it) => {
    const [fort, suite] = Array.isArray(it) ? it : [null, it];
    return {
      text: fort ? [{ text: fort, options: { bold: true, color: ENCRE } },
                    { text: suite ? ' — ' + suite : '', options: { color: GRIS } }]
                 : [{ text: suite, options: { color: GRIS } }],
    };
  });

  s.addText(
    lignes.flatMap((l, i) => l.text.map((t, j) => ({
      ...t,
      options: { ...t.options, breakLine: j === l.text.length - 1 },
    }))),
    {
      x: 0.62, y, w: 8.8, h: H - y - 0.5, ...TEXTE, fontSize: 15,
      bullet: { code: '25AA', indent: 18 }, lineSpacingMultiple: 1.45, paraSpaceAfter: 8,
    },
  );
  if (note) s.addNotes(note);
  return s;
}

/** Deux colonnes qui s'opposent : le reflexe habituel, et notre choix. */
function opposition({ titre, chapeau, gauche, droite, lignes, note }) {
  const { s, y } = page(titre, chapeau);

  const enTete = (texte, x, couleur) => {
    s.addShape(pres.ShapeType.rect, { x, y, w: 4.15, h: 0.42, fill: { color: couleur } });
    s.addText(texte, {
      x, y, w: 4.15, h: 0.42, ...TEXTE, fontSize: 12, bold: true, color: BLANC,
      align: 'center', valign: 'middle', charSpacing: 1,
    });
  };
  enTete(gauche, 0.62, GRIS);
  enTete(droite, 5.23, VERT);

  let yy = y + 0.55;
  for (const [a, b] of lignes) {
    const haut = 0.72;
    s.addShape(pres.ShapeType.rect, { x: 0.62, y: yy, w: 4.15, h: haut, fill: { color: BLANC }, line: { color: GRIS_CLAIR, width: 1 } });
    s.addText(a, { x: 0.77, y: yy, w: 3.85, h: haut, ...TEXTE, fontSize: 12, color: GRIS, valign: 'middle' });

    s.addShape(pres.ShapeType.rect, { x: 5.23, y: yy, w: 4.15, h: haut, fill: { color: BLANC }, line: { color: VERT, width: 1 } });
    s.addText(b, { x: 5.38, y: yy, w: 3.85, h: haut, ...TEXTE, fontSize: 12, color: ENCRE, valign: 'middle' });

    yy += haut + 0.12;
  }
  if (note) s.addNotes(note);
  return s;
}

/** Trois grands chiffres, et ce qu'ils veulent dire. */
function chiffres({ titre, chapeau, blocs, bas, note }) {
  const { s, y } = page(titre, chapeau);

  const large = 2.75, ecart = 0.28;
  blocs.forEach((b, i) => {
    const x = 0.62 + i * (large + ecart);
    s.addShape(pres.ShapeType.rect, { x, y: y + 0.1, w: large, h: 1.75, fill: { color: BLANC }, line: { color: GRIS_CLAIR, width: 1 } });
    s.addShape(pres.ShapeType.rect, { x, y: y + 0.1, w: large, h: 0.06, fill: { color: VERT } });
    s.addText(b.valeur, {
      x, y: y + 0.32, w: large, h: 0.8, ...TITRE, fontSize: 44, color: VERT, align: 'center',
    });
    s.addText(b.libelle, {
      x: x + 0.15, y: y + 1.15, w: large - 0.3, h: 0.6, ...TEXTE, fontSize: 12, color: GRIS,
      align: 'center', lineSpacingMultiple: 1.15,
    });
  });

  if (bas) {
    s.addText(bas, {
      x: 0.62, y: y + 2.15, w: 8.8, h: 1.1, ...TEXTE, fontSize: 14, color: ENCRE,
      lineSpacingMultiple: 1.35,
    });
  }
  if (note) s.addNotes(note);
  return s;
}

/** Une affirmation seule, qu'on veut voir rester. */
function citation({ texte, appui, note }) {
  const s = pres.addSlide();
  s.background = { color: VERT };

  s.addText('“', { x: 0.7, y: 0.5, w: 1, h: 1, ...TITRE, fontSize: 90, color: VERT_SOMBRE });
  s.addText(texte, {
    x: 1.0, y: 1.5, w: 8.0, h: 1.6, ...TITRE, fontSize: 32, color: BLANC, lineSpacingMultiple: 1.2,
  });
  if (appui) {
    s.addText(appui, {
      x: 1.0, y: 3.3, w: 8.0, h: 1.3, ...TEXTE, fontSize: 15, color: 'D6F5F0', lineSpacingMultiple: 1.35,
    });
  }
  if (note) s.addNotes(note);
  return s;
}

/** Un tableau d'avancement. */
function tableau({ titre, chapeau, entetes, lignes, note }) {
  const { s, y } = page(titre, chapeau);

  const corps = [
    entetes.map((h) => ({
      text: h,
      options: { bold: true, color: BLANC, fill: { color: VERT }, fontSize: 12 },
    })),
    ...lignes.map((l) =>
      l.map((c, i) => {
        const fini = /Livré/.test(c);
        const cours = /En cours/.test(c);
        return {
          text: c,
          options: {
            fontSize: 12,
            color: i === 0 ? ENCRE : cours ? AMBRE : fini ? VERT_SOMBRE : GRIS,
            bold: i > 0,
            align: i === 0 ? 'left' : 'center',
            fill: { color: BLANC },
          },
        };
      }),
    ),
  ];

  s.addTable(corps, {
    x: 0.62, y, w: 8.8, colW: [6.6, 2.2],
    border: { type: 'solid', color: GRIS_CLAIR, pt: 1 },
    ...TEXTE, valign: 'middle', rowH: 0.31,
  });
  if (note) s.addNotes(note);
  return s;
}

// =============================================================================
//  LE CONTENU
// =============================================================================

couverture({
  titre: 'GARAH',
  sousTitre: 'Vendre, servir et acheminer\nentre le Cameroun et la Centrafrique',
  pied: 'Dossier de présentation aux partenaires',
  note:
    "Ouvrir simplement : GARAH est une plateforme qui relie trois métiers aujourd'hui séparés — " +
    "la vente, le service client et le transport — sur l'axe Douala–Bertoua–Bangui.\n\n" +
    "Ne pas entrer dans la technique : la présentation y vient à la fin, et brièvement.",
});

// --- 1. Le problème ----------------------------------------------------------
partie({
  numero: 1, titre: 'Le problème',
  phrase: "Ce que coûte, aujourd'hui, l'absence d'outil sur le corridor.",
  note: "Poser le décor avant de parler de la solution. Le partenaire doit reconnaître une situation qu'il connaît.",
});

puces({
  titre: 'Aujourd’hui, entre Douala et Bangui',
  chapeau: 'Le commerce fonctionne, mais sans mémoire.',
  items: [
    ['La commande', 'se prend par téléphone ou par messagerie'],
    ['Le paiement', 'se note sur un cahier'],
    ['Le colis', "part chez un transporteur, et sort du système"],
    ['Au client qui demande où il en est', "on répond « il est parti »"],
  ],
  note:
    "Insister : rien de tout cela n'est du désordre. Ce sont des gens sérieux avec de mauvais outils.\n\n" +
    "Le point qui fait mal est le dernier : « il est parti » n'est pas une réponse, et c'est la seule qu'on puisse donner.",
});

puces({
  titre: 'Ce que cela coûte',
  items: [
    ['Le litige sans preuve', "sans historique daté, un désaccord se règle à la parole contre la parole. On perd le client, ou on rembourse à tort."],
    ['L’argent qui s’évapore', "une commission oubliée sur une vente ne se remarque qu'en fin de mois, quand elle n'est plus récupérable."],
    ['Le client qui rappelle', "« où est mon colis ? » occupe un employé plusieurs fois par jour, pour une réponse que le système devrait donner seul."],
  ],
  note:
    "Ce sont les trois pertes à retenir. Chacune a une réponse directe dans la suite de la présentation :\n" +
    "— le litige → le journal d'événements ;\n" +
    "— la commission → le grand livre automatique ;\n" +
    "— le rappel client → le suivi public par SMS.\n\n" +
    "Si l'interlocuteur ne retient qu'une chose de cette partie, c'est celle-ci.",
});

// --- 2. La réponse -----------------------------------------------------------
partie({
  numero: 2, titre: 'La réponse',
  phrase: 'Une seule plateforme, quatre métiers qui se parlent.',
  note: "Transition : on ne remplace pas le commerçant, on lui donne la mémoire qui lui manque.",
});

puces({
  titre: 'Quatre métiers, un seul système',
  chapeau: "Ce qui vivait dans un cahier, une messagerie et un carnet de transporteur.",
  items: [
    ['Vendre', 'boutique en ligne, prix dégressifs, négociation, Mobile Money'],
    ['Servir', 'service client, réclamations, retours'],
    ['Acheminer', 'points de transit, suivi de colis, comptoir de remise'],
    ['Compter', 'grand livre par marchand, règlements, statistiques'],
  ],
  note:
    "Le mot important est « se parlent ». Une vente écrit sa ligne comptable toute seule. " +
    "Un colis écrit son parcours. Une réclamation retrouve la commande.\n\n" +
    "C'est ce recoupement automatique qui n'existe pas quand les outils sont séparés — " +
    "et c'est là que se logent les pertes.",
});

// --- 3. Vendre ---------------------------------------------------------------
partie({
  numero: 3, titre: 'Vendre',
  phrase: 'Un catalogue qui ressemble au commerce réel, pas à un modèle importé.',
});

puces({
  titre: 'Le catalogue',
  items: [
    ['Des déclinaisons', "un article existe en tailles, couleurs, conditionnements ; chacune a son prix et son stock"],
    ['Des prix dégressifs', "un tarif de 1 à 6 pièces, un autre au-delà — c'est le commerce de gros, pas une option"],
    ['La négociation outillée', "le client discute avec un conseiller ; une proposition acceptée devient une commande"],
    ['Le Mobile Money', 'MTN MoMo et Orange Money, les moyens réellement utilisés'],
  ],
  note:
    "Le point à défendre est le deuxième. Beaucoup de plateformes imposent UN prix par article : " +
    "elles sont conçues pour le détail occidental.\n\n" +
    "Ici le prix dépend de la quantité, parce que c'est ainsi qu'on achète sur ce corridor. " +
    "Un marchand qui vend au carton ne peut pas travailler autrement.\n\n" +
    "Sur la négociation : le marchandage n'est pas contourné, il est outillé. C'est un argument fort " +
    "auprès de quelqu'un qui connaît le terrain.",
});

citation({
  texte: 'Les montants d’une commande sont figés au moment de l’achat.',
  appui:
    "Désignation, prix, taxe, commission, frais de livraison.\n" +
    "Changer un tarif aujourd’hui ne réécrit pas une facture de mars.",
  note:
    "Diapositive à laisser respirer. C'est une règle de comptabilité, pas un détail technique.\n\n" +
    "Sans elle, toute la comptabilité passée devient fausse à chaque mise à jour de prix — " +
    "et personne ne s'en aperçoit avant un contrôle ou un litige.\n\n" +
    "Si on vous demande « comment vous faites ? » : chaque ligne de commande garde une COPIE " +
    "des montants, elle ne pointe pas vers le tarif du jour.",
});

// --- 4. Servir ---------------------------------------------------------------
partie({
  numero: 4, titre: 'Servir',
  phrase: 'Le service client, structuré — et séparé du retour de marchandise.',
});

puces({
  titre: 'Le service client',
  items: [
    ['Une file partagée', 'le premier conseiller disponible prend le dossier'],
    ['Réclamation et retour ne se confondent pas', "l'une est une plainte qu'un humain tranche, l'autre est une marchandise qui revient"],
    ['Un retour en cinq étapes', 'accepté, reçu, contrôlé, validé, clôturé'],
    ['L’évaluation après clôture seulement', "demander un avis sur un problème non résolu mesure l'agacement, pas le service"],
  ],
  note:
    "Le troisième point mérite qu'on s'y arrête — la diapositive suivante l'explique.\n\n" +
    "Sur le premier : la file est partagée, et le premier qui clique gagne le dossier. " +
    "Cela évite qu'une réclamation attende parce que la personne à qui elle était assignée est absente.",
});

citation({
  texte: 'Un retour accepté n’est pas un retour reçu.',
  appui:
    "Confondre les deux, c’est rembourser une marchandise qui n’est jamais rentrée.\n\n" +
    "Le client déclare un montant ; l’entreprise en constate un autre après ouverture du colis. " +
    "Les deux chiffres sont conservés.",
  note:
    "C'est l'exemple le plus parlant de la philosophie du système.\n\n" +
    "Un bouton « retour traité » serait plus simple à construire — et il ferait sortir de l'argent " +
    "pour des colis vides. Les cinq étapes existent parce que chacune est un moment où quelqu'un " +
    "constate quelque chose de différent.\n\n" +
    "Les deux chiffres conservés (déclaré / constaté) sont ce qui permet de trancher un litige.",
});

// --- 5. Acheminer ------------------------------------------------------------
partie({
  numero: 5, titre: 'Acheminer',
  phrase: 'Douala — Bertoua — Bangui, étape par étape.',
});

puces({
  titre: 'Le corridor, outillé',
  items: [
    ['Points de transit et de récupération', 'le client choisit où il vient chercher sa commande'],
    ['Itinéraires réutilisables', "ils annoncent un délai sans l'imposer : une route coupée, cela arrive"],
    ['Suivi par événements datés', '« réceptionné à Bertoua le 12/03 à 14 h par David »'],
    ['Suivi public', 'le client entre le numéro reçu par SMS — sans compte, sans mot de passe'],
  ],
  note:
    "Le suivi public est l'argument commercial le plus immédiat : il supprime les appels " +
    "« où est mon colis ? », et il se partage par SMS comme le font déjà les gens.\n\n" +
    "Sur les itinéraires : le système annonce un délai mais n'interdit pas de s'en écarter. " +
    "Un logiciel qui refuse la réalité fait saisir n'importe quoi d'autre — et on perd la trace.",
});

citation({
  texte: 'Au comptoir : voir, puis remettre.',
  appui:
    "L’agent saisit le code, VOIT les colis qu’il désigne, puis confirme.\n\n" +
    "Fusionner recherche et confirmation « pour gagner un clic » rendrait la remise aveugle : " +
    "le code validé, la marchandise au hasard.",
  note:
    "Petit détail, grosse conséquence. C'est le genre d'arbitrage qui distingue un logiciel " +
    "pensé pour le terrain d'un logiciel pensé pour la démonstration.\n\n" +
    "À utiliser si l'interlocuteur demande « qu'est-ce qui vous différencie d'une solution du marché ? ».",
});

// --- 6. Compter --------------------------------------------------------------
partie({
  numero: 6, titre: 'Compter',
  phrase: 'Une comptabilité marchand qui tient devant un tiers.',
});

puces({
  titre: 'Le grand livre',
  items: [
    ['Une ligne par vente', 'commission comprise, écrite automatiquement'],
    ['Le solde est une somme d’écritures', "jamais un total conservé à part — un total conservé finit toujours par mentir"],
    ['Règlement en deux temps', "préparer, puis confirmer : la dette ne baisse qu'au départ réel de l'argent"],
    ['Correction par ajout', "une écriture fausse s'annule par une autre, avec son motif et son auteur"],
  ],
  note:
    "C'est la partie qui rassure un investisseur ou un comptable.\n\n" +
    "Le deuxième point est le plus important : on ne stocke jamais « ce marchand nous doit X ». " +
    "On additionne les lignes à chaque affichage. Un total stocké se désynchronise dès la première " +
    "opération manquée, et plus personne ne sait laquelle.\n\n" +
    "La référence de virement est obligatoire : c'est la seule preuve six mois plus tard.",
});

puces({
  titre: 'Piloter',
  chapeau: 'Ce que le dirigeant voit sans rien demander à personne.',
  items: [
    ['Chiffre d’affaires et articles vendus', 'sur la période choisie'],
    ['Fiches consultées', "l'audience, distincte des ventes — ce qu'on regarde sans acheter"],
    ['Les produits qui montent', 'pour décider quoi réapprovisionner'],
    ['Export PDF, Word et Excel', "à l'en-tête de la marque, en un clic"],
  ],
  note:
    "L'export est un argument concret : un partenaire ou un banquier qui demande des chiffres " +
    "reçoit un document propre, pas une capture d'écran.\n\n" +
    "Sur l'audience : savoir qu'un produit est très consulté et peu acheté est une information " +
    "commerciale — le prix ou la photo ne vont pas.",
});

// --- 7. Ce qui distingue -----------------------------------------------------
partie({
  numero: 7, titre: 'Ce qui distingue GARAH',
  phrase: 'Un système pensé pour le litige, pas seulement pour la vente.',
  note: "Partie centrale si le temps manque. C'est ici que se joue la différence avec une solution générique.",
});

opposition({
  titre: 'Quatre arbitrages',
  chapeau: 'La plupart des outils enregistrent ce qui se passe quand tout va bien.',
  gauche: 'LE RÉFLEXE HABITUEL',
  droite: 'LE CHOIX DE GARAH',
  lignes: [
    ['Une colonne « statut » qu’on écrase', 'Un journal d’événements : chaque étape est gardée'],
    ['Supprimer ce qui ne sert plus', 'Désactiver : des factures pointent vers ces lignes'],
    ['Un solde stocké quelque part', 'Un solde recalculé, donc toujours juste'],
    ['Un bouton qui échoue et affiche « erreur »', 'L’écran dit ce qui manque avant le clic'],
  ],
  note:
    "Chaque ligne de droite coûte plus cher à construire que celle de gauche. C'est un investissement " +
    "délibéré, et il se rembourse au premier litige sérieux.\n\n" +
    "Formule utile : « nous avons construit pour le jour où ça se passe mal, parce que c'est ce " +
    "jour-là que l'argent se perd ».",
});

citation({
  texte: 'Un agent de service client ne peut pas déclencher un remboursement.',
  appui:
    "Trancher un litige en faveur du client et rendre l’argent sont deux décisions séparées, " +
    "avec deux autorisations différentes.\n\n" +
    "188 autorisations distinctes, assemblées en profils métier.",
  note:
    "La séparation des pouvoirs. Personne ne peut, seul, faire sortir de l'argent.\n\n" +
    "Les 188 autorisations ne se donnent pas une par une : on assemble des profils " +
    "(« agent de comptoir », « service client », « gestionnaire de catalogue ») et on affecte " +
    "les gens à des profils.\n\n" +
    "Argument à sortir si on vous parle de fraude interne ou de contrôle.",
});

puces({
  titre: 'Conçu pour les conditions locales',
  items: [
    ['Léger sur les connexions lentes', "une centaine de kilo-octets au chargement ; le reste ne vient qu'à l'usage"],
    ['Trois langues prévues dès l’origine', 'français, anglais, sango'],
    ['Le franc CFA', 'comme monnaie de référence'],
    ['Le Mobile Money', "comme moyen principal, pas comme option ajoutée après coup"],
  ],
  note:
    "Point différenciant face à une solution importée : celles-ci supposent la fibre, la carte " +
    "bancaire et l'euro.\n\n" +
    "Le sango n'est pas un détail : c'est la langue véhiculaire en Centrafrique, et personne " +
    "d'autre ne la prévoit.",
});

// --- 8. Où en est le projet --------------------------------------------------
partie({
  numero: 8, titre: 'Où en est le projet',
  phrase: 'Neuf lots sur onze livrés. Le système tourne.',
});

tableau({
  titre: 'L’avancement',
  entetes: ['Domaine', 'État'],
  lignes: [
    ['Catalogue, déclinaisons, grilles de prix', 'Livré'],
    ['Commandes et paiements', 'Livré'],
    ['Stocks et mouvements', 'Livré'],
    ['Logistique : lieux, itinéraires, expéditions, suivi, comptoir', 'Livré'],
    ['Réclamations et retours', 'Livré'],
    ['Service client et négociation', 'Livré'],
    ['Finance marchand : grand livre, règlements, commissions', 'Livré'],
    ['Clients, surveillance et audit', 'Livré'],
    ['Statistiques et exports', 'Livré'],
    ['Boutique vue par le client final', 'En cours'],
  ],
  note:
    "Ne pas survoler : c'est la diapositive qui établit la crédibilité.\n\n" +
    "Le seul « en cours » est la vitrine client. Autrement dit : le moteur est construit, " +
    "il faut poser la carrosserie. C'est l'inverse de la plupart des projets, où l'on montre " +
    "une belle façade sans rien derrière.\n\n" +
    "Le back-office est utilisé aujourd'hui, en production, sur des données réelles.",
});

chiffres({
  titre: 'Les chiffres',
  chapeau: 'Tous vérifiables — rien n’est arrondi.',
  blocs: [
    { valeur: '64', libelle: 'tables de données\nstructurées' },
    { valeur: '188', libelle: 'autorisations\ndistinctes' },
    { valeur: '345', libelle: 'contrôles automatiques\nau vert' },
  ],
  bas:
    "Les 345 contrôles automatiques sont des vérifications que la machine rejoue à chaque " +
    "modification. Elles répondent à une question simple : « est-ce que ce qui marchait hier " +
    "marche encore aujourd’hui ? »",
  note:
    "Ne pas laisser ces chiffres passer pour de la décoration. Le seul qui compte vraiment " +
    "pour un partenaire est le troisième — la diapositive suivante explique pourquoi.",
});

citation({
  texte: 'Pourquoi 345 contrôles est un argument financier.',
  appui:
    "SANS eux : chaque évolution risque de casser silencieusement autre chose, et le défaut " +
    "apparaît chez le client. Le coût de maintenance grimpe jusqu’à ce que plus personne " +
    "n’ose toucher au système.\n\n" +
    "AVEC eux : une régression est détectée en quelques minutes, avant la mise en ligne.",
  note:
    "La traduction à faire à voix haute : « le projet est évolutif sur la durée, pas seulement " +
    "fonctionnel aujourd'hui ».\n\n" +
    "C'est ce qui sépare un logiciel qu'on peut faire grandir d'un logiciel qu'il faudra " +
    "réécrire dans trois ans. Un partenaire qui a déjà financé un projet informatique " +
    "comprendra immédiatement.",
});

// --- 9. Les fondations -------------------------------------------------------
partie({
  numero: 9, titre: 'Les fondations',
  phrase: 'Des choix courants, pas exotiques. Cette partie est volontairement brève.',
});

puces({
  titre: 'Ce sur quoi c’est construit',
  items: [
    ['Java / Spring Boot', 'la technologie des banques et des assurances ; compétences largement disponibles'],
    ['PostgreSQL', 'base de données libre et éprouvée, sans coût de licence'],
    ['Angular', 'maintenu par Google, adapté aux applications de gestion'],
    ['Microsoft Azure, région France', 'serveur toujours actif, sans temps de réveil'],
  ],
  note:
    "Ne pas s'attarder — le public n'est pas technicien. Le seul message est : aucune brique " +
    "exotique, aucune brique propriétaire, des compétences qui se recrutent.\n\n" +
    "Si on demande « et si vous partez ? » : c'est exactement à cette question que répond " +
    "cette diapositive.",
});

puces({
  titre: 'Deux garanties d’exploitation',
  items: [
    ['Aucune dépendance à un fournisseur unique', "le système a déjà été déplacé d'un hébergeur à un autre en cours de route, sans réécriture"],
    ['Mise en ligne automatisée', "chaque modification passe les 345 contrôles avant d'être déployée ; une version défectueuse n'atteint pas la production"],
  ],
  note:
    "Le premier point est une preuve, pas une promesse : le déménagement a réellement eu lieu, " +
    "et il n'a rien cassé.\n\n" +
    "Le second répond à la peur classique : « et si une mise à jour casse tout un samedi soir ? »",
});

// --- 10. La suite ------------------------------------------------------------
partie({
  numero: 10, titre: 'La suite',
  phrase: 'Ce qu’un partenariat permettrait d’accélérer.',
});

puces({
  titre: 'Le dernier grand chantier',
  chapeau: 'La boutique vue par le client final.',
  items: [
    ['Vitrine et catégories', 'le catalogue public'],
    ['Fiche produit', 'choix de la déclinaison, prix selon la quantité'],
    ['Panier et commande', 'adresse, point de récupération, récapitulatif'],
    ['Paiement', 'MTN MoMo et Orange Money'],
    ['Mes commandes et suivi', 'où en est chaque colis'],
  ],
  note:
    "Préciser que le serveur expose DÉJÀ tout ce dont ces écrans ont besoin : catalogue, panier, " +
    "commande, paiement, suivi, réclamations, conversations.\n\n" +
    "Il ne s'agit pas d'inventer, il s'agit de construire les écrans. Le risque est faible " +
    "et le périmètre est connu.",
});

puces({
  titre: 'Ce qu’un partenariat accélérerait',
  items: [
    ['Finir la boutique client', 'et ouvrir la plateforme au public'],
    ['Recruter les marchands du corridor', 'et mettre leurs catalogues en ligne'],
    ['Équiper les points de récupération', "le comptoir existe, il faut le déployer sur le terrain"],
    ['Conventionner les opérateurs Mobile Money', 'aux conditions du volume'],
  ],
  note:
    "Adapter cette diapositive à l'interlocuteur : un investisseur financier, un partenaire " +
    "logistique et un marchand n'entendent pas la même chose.\n\n" +
    "Garder l'ordre : la boutique d'abord, parce que rien ne se vend sans elle.",
});

citation({
  texte: 'Le risque technique principal est derrière nous.',
  appui:
    "Construire un système fiable qui tienne ensemble la comptabilité, la logistique et le " +
    "service client : c’est fait. Il tourne, il est vérifié en continu, il est documenté.\n\n" +
    "Ce qui reste relève de l’exécution commerciale et du déploiement terrain.",
  note:
    "La phrase à laisser en dernier avant la clôture. C'est le message que le partenaire doit " +
    "emporter.\n\n" +
    "Un projet informatique échoue le plus souvent AVANT d'être fini. Ici, la partie la plus " +
    "risquée est passée.",
});

couverture({
  titre: 'GARAH',
  sousTitre: 'Vendre, servir et acheminer\nentre le Cameroun et la Centrafrique',
  pied: 'Merci — et à votre disposition pour les questions.',
  note:
    "Questions probables et où trouver la réponse :\n\n" +
    "— « Combien ça coûte à faire tourner ? » → partie 9, hébergement standard, pas de licence.\n" +
    "— « Qu'est-ce qui vous protège d'un concurrent ? » → partie 7 : les arbitrages, le sango, " +
    "le Mobile Money, la connaissance du corridor.\n" +
    "— « Combien de temps pour finir ? » → partie 10 : périmètre connu, serveur déjà prêt.\n" +
    "— « Et si vous partez ? » → partie 9 : technologies courantes, 31 décisions documentées.",
});

// -----------------------------------------------------------------------------
// Le fichier se pose à côté de ce script, quel que soit le dossier courant.
const SORTIE = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'GARAH-presentation.pptx',
);
await pres.writeFile({ fileName: SORTIE });
console.log('ecrit :', SORTIE);
