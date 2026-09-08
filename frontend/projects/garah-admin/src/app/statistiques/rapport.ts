import { montantLisible, tauxLisible } from 'garah-ui';

// ⚠️ `import type` et non `import` : TypeScript l'efface à la compilation.
// Les classes de `docx` restent chargées au clic, dans versWord() ; seuls
// leurs types viennent ici, et ils ne pèsent rien dans le paquet livré.
import type { Paragraph as TypeParagraphe, Table as TypeTableau } from 'docx';

// =============================================================================
// Un bilan, écrit dans un fichier
// =============================================================================
// 🎯 UNE SOURCE, TROIS RENDUS.
//
// L'écran décrit UNE fois ce qu'il montre — des sections, des colonnes typées,
// des lignes — et chaque format se contente de le dessiner. Écrire le contenu
// trois fois garantirait qu'un jour le PDF et le tableur ne diraient plus la
// même chose, sans que rien ne le signale : c'est le genre d'écart qu'on
// découvre en réunion, devant quelqu'un qui a les deux fichiers.
//
// ⚠️ LES BIBLIOTHÈQUES SONT CHARGÉES AU CLIC, jamais avant.
//
// jsPDF, docx et write-excel-file pèsent ensemble bien plus que l'application
// entière. Un `import` en tête de fichier les mettrait dans le paquet de
// l'écran : tout le monde les téléchargerait, y compris ceux qui viennent
// seulement LIRE leurs chiffres. L'import dynamique en fait trois paquets à
// part, demandés au moment du clic et jamais avant.
//
// ⚠️ Le serveur n'est pas rappelé. On écrit ce qui est AFFICHÉ — un export qui
// referait sa propre requête finirait par livrer des chiffres différents de
// ceux qu'on regardait.
// =============================================================================

/** Ce que contient une colonne — ce qui décide de son écriture et de son fer. */
export type TypeColonne = 'texte' | 'nombre' | 'montant' | 'taux' | 'date';

export interface Colonne {
  readonly titre: string;
  readonly type: TypeColonne;
}

export type Valeur = string | number | Date | null;

/** Un tableau du rapport : un titre, des colonnes, des lignes. */
export interface Section {
  readonly titre: string;
  readonly colonnes: readonly Colonne[];
  readonly lignes: readonly (readonly Valeur[])[];
}

export interface Rapport {
  readonly titre: string;
  readonly periode: string;
  readonly sections: readonly Section[];
  /** Le nom du fichier, sans extension. */
  readonly fichier: string;
}

// -----------------------------------------------------------------------------
// Écriture des valeurs
// -----------------------------------------------------------------------------

/**
 * La valeur telle qu'un humain la lit.
 *
 * <p>Pour le PDF et le document Word, qui affichent du texte. Le tableur, lui,
 * reçoit le <b>nombre brut</b> : un chiffre d'affaires écrit « 1 200 FCFA »
 * dans une cellule ne s'additionne pas, et un export qu'on ne peut pas
 * totaliser n'a aucun intérêt.</p>
 */
function lisible(valeur: Valeur, type: TypeColonne): string {
  if (valeur === null) {
    return '—';
  }
  if (valeur instanceof Date) {
    return valeur.toLocaleDateString('fr-FR');
  }
  switch (type) {
    case 'montant':
      return montantLisible(Number(valeur), 'XAF');
    case 'taux':
      return tauxLisible(Number(valeur));
    case 'nombre':
      return new Intl.NumberFormat('fr-FR').format(Number(valeur));
    default:
      return String(valeur);
  }
}

/**
 * Le même texte, mais écrivable avec les polices d'un PDF.
 *
 * <p>⚠️ {@code Intl.NumberFormat('fr-FR')} sépare les milliers par une espace
 * fine insécable — U+202F. Les polices standard d'un PDF sont encodées en
 * WinAnsi, qui ne la contient pas : un montant en sortirait avec un caractère
 * parasite au milieu du nombre. U+00A0, l'insécable ordinaire, arrive par le
 * même chemin sur d'autres navigateurs.</p>
 *
 * <p>Les deux sont écrites en ÉCHAPPEMENT et non collées telles quelles :
 * ce sont des caractères invisibles, et personne ne devinerait ce que cette
 * expression cherche en la relisant.</p>
 */
function pourPdf(texte: string): string {
  return texte.replace(/[\u202f\u00a0]/g, ' ');
}

/** Les colonnes de chiffres se ferrent à droite : c'est ce qui aligne les unités. */
function estNumerique(type: TypeColonne): boolean {
  return type === 'nombre' || type === 'montant' || type === 'taux';
}

// -----------------------------------------------------------------------------
// PDF
// -----------------------------------------------------------------------------

/** Le vert de la marque, en composantes — jsPDF ne lit pas les couleurs CSS. */
const MARQUE: [number, number, number] = [18, 165, 148];

export async function versPdf(r: Rapport): Promise<void> {
  const [{ jsPDF }, { autoTable }] = await Promise.all([
    import('jspdf'),
    import('jspdf-autotable'),
  ]);

  // Portrait : un bilan se lit et s'imprime, il ne se projette pas.
  const doc = new jsPDF({ orientation: 'portrait', unit: 'pt', format: 'a4' });
  const marge = 40;

  doc.setFontSize(18);
  doc.text(pourPdf(r.titre), marge, 52);

  doc.setFontSize(10);
  doc.setTextColor(110);
  doc.text(pourPdf(r.periode), marge, 70);
  doc.setTextColor(0);

  let y = 96;

  for (const section of r.sections) {
    doc.setFontSize(12);
    doc.text(pourPdf(section.titre), marge, y);

    autoTable(doc, {
      startY: y + 10,
      margin: { left: marge, right: marge },
      head: [section.colonnes.map((c) => c.titre)],
      body: section.lignes.map((ligne) =>
        ligne.map((v, i) => pourPdf(lisible(v, section.colonnes[i].type))),
      ),
      theme: 'striped',
      styles: { fontSize: 9, cellPadding: 5 },
      headStyles: { fillColor: MARQUE, textColor: 255, fontStyle: 'bold' },
      columnStyles: Object.fromEntries(
        section.colonnes.map((c, i) => [i, { halign: estNumerique(c.type) ? 'right' : 'left' }]),
      ),
    });

    // Où le tableau s'est arrêté. Sans cela, le titre suivant s'écrirait
    // par-dessus lui — la hauteur d'un tableau n'est connue qu'après le tracé.
    y = (doc as unknown as { lastAutoTable?: { finalY?: number } }).lastAutoTable?.finalY ?? y;
    y += 32;
  }

  enregistrer(doc.output('blob'), `${r.fichier}.pdf`);
}

// -----------------------------------------------------------------------------
// Tableur
// -----------------------------------------------------------------------------

/**
 * Les formats de cellule, par type de colonne.
 *
 * <p>Ce sont ceux d'Excel, pas les nôtres : la cellule contient le nombre, le
 * format dit seulement comment l'afficher. Changer la devise dans le tableau
 * n'abîme donc aucun total.</p>
 */
const FORMATS: Partial<Record<TypeColonne, string>> = {
  montant: '#,##0 "FCFA"',
  nombre: '#,##0',
  taux: '0.0 %',
  date: 'dd/mm/yyyy',
};

export async function versTableur(r: Rapport): Promise<void> {
  const { default: ecrireXlsx } = await import('write-excel-file/browser');

  // Une feuille par section, et non trois tableaux empilés : c'est ce qui
  // permet de trier ou de filtrer un classement sans déranger le reste.
  const feuilles = r.sections.map((section) => ({
    sheet: section.titre.slice(0, 31), // Excel refuse au-delà de 31 caractères.
    columns: section.colonnes.map((c) => ({ width: c.type === 'texte' ? 34 : 16 })),
    data: [
      section.colonnes.map((c) => ({
        value: c.titre,
        fontWeight: 'bold' as const,
        backgroundColor: '#12a594',
        color: '#ffffff',
      })),
      ...section.lignes.map((ligne) =>
        ligne.map((v, i) => cellule(v, section.colonnes[i].type)),
      ),
    ],
  }));

  const blob = await ecrireXlsx(feuilles).toBlob();
  enregistrer(blob, `${r.fichier}.xlsx`);
}

/** Une cellule de tableur : la valeur BRUTE, plus le format qui l'affiche. */
function cellule(valeur: Valeur, type: TypeColonne) {
  if (valeur === null) {
    return null;
  }
  if (valeur instanceof Date) {
    return { value: valeur, type: Date, format: FORMATS.date };
  }
  if (estNumerique(type)) {
    return { value: Number(valeur), type: Number, format: FORMATS[type] };
  }
  return { value: String(valeur), type: String };
}

// -----------------------------------------------------------------------------
// Document Word
// -----------------------------------------------------------------------------

export async function versWord(r: Rapport): Promise<void> {
  const { Document, Packer, Paragraph, Table, TableCell, TableRow, TextRun, HeadingLevel, WidthType, AlignmentType } =
    await import('docx');

  const pleine = { size: 100, type: WidthType.PERCENTAGE } as const;

  const enTete = (texte: string) =>
    new TableCell({
      shading: { fill: '12A594' },
      children: [
        new Paragraph({
          children: [new TextRun({ text: texte, bold: true, color: 'FFFFFF' })],
        }),
      ],
    });

  const corps = (texte: string, droite: boolean) =>
    new TableCell({
      children: [
        new Paragraph({
          text: texte,
          alignment: droite ? AlignmentType.RIGHT : AlignmentType.LEFT,
        }),
      ],
    });

  const contenu: (TypeParagraphe | TypeTableau)[] = [
    new Paragraph({ text: r.titre, heading: HeadingLevel.HEADING_1 }),
    new Paragraph({ children: [new TextRun({ text: r.periode, italics: true, color: '6B7280' })] }),
    new Paragraph({ text: '' }),
  ];

  for (const section of r.sections) {
    contenu.push(new Paragraph({ text: section.titre, heading: HeadingLevel.HEADING_2 }));
    contenu.push(
      new Table({
        width: pleine,
        rows: [
          new TableRow({ children: section.colonnes.map((c) => enTete(c.titre)) }),
          ...section.lignes.map(
            (ligne) =>
              new TableRow({
                children: ligne.map((v, i) =>
                  corps(lisible(v, section.colonnes[i].type), estNumerique(section.colonnes[i].type)),
                ),
              }),
          ),
        ],
      }),
    );
    // Word colle deux tableaux consécutifs et n'en fait plus qu'un. Ce
    // paragraphe vide est ce qui les sépare.
    contenu.push(new Paragraph({ text: '' }));
  }

  const blob = await Packer.toBlob(new Document({ sections: [{ children: contenu }] }));
  enregistrer(blob, `${r.fichier}.docx`);
}

// -----------------------------------------------------------------------------
// Le téléchargement
// -----------------------------------------------------------------------------

/** Donne un fichier à enregistrer, sans passer par le serveur. */
function enregistrer(blob: Blob, nom: string): void {
  const url = URL.createObjectURL(blob);
  const lien = document.createElement('a');
  lien.href = url;
  lien.download = nom;
  lien.click();

  // Sans cela le fichier reste en mémoire jusqu'au rechargement de la page.
  URL.revokeObjectURL(url);
}
