# Présentation aux partenaires

| Fichier | Quoi |
|---|---|
| `garah-dossier.tex` | Le dossier écrit, ~12 pages — à compiler sur Overleaf |
| `GARAH-presentation.pptx` | 34 diapositives, mises en forme, avec les notes de l'orateur |
| `engendrer-presentation.mjs` | Le code qui produit le `.pptx` — à modifier si le contenu change |

---

## Le parti pris

Les deux documents s'adressent à des partenaires **non informaticiens**.

- Le sujet est le **commerce**, pas la technologie.
- La technique n'apparaît qu'en fin de parcours, et réduite au minimum.
- Chaque choix technique est traduit en **conséquence pour l'entreprise** :
  « 345 contrôles automatiques » n'est pas un chiffre de développeur, c'est la
  raison pour laquelle le coût de maintenance ne va pas exploser.
- Les chiffres avancés sont **vérifiables dans le dépôt** : 64 tables,
  188 autorisations, 345 tests, 31 décisions documentées, 9 lots sur 11.

---

## Les diapositives

### Ce qu'il y a dedans

**34 diapositives**, réparties en dix parties. Chacune est ouverte par une
diapositive de titre sombre numérotée, ce qui donne le rythme et permet de
sauter une partie sans perdre l'auditoire.

Trois formes reviennent :

| Forme | Quand |
|---|---|
| **Fond clair, points** | Le contenu courant. Le terme en gras, l'explication en gris. |
| **Fond vert, une phrase** | Une affirmation qu'on veut voir rester. Sept dans le jeu. |
| **Fond sombre, un chiffre** | L'ouverture d'une partie. |

### ⚠️ Les notes de l'orateur

**Les 34 diapositives portent une note.** Elle dit ce qu'il faut expliquer en
la montrant, ce qu'il ne faut pas survoler, et quelle formule employer.

Pour les voir : dans PowerPoint, **Affichage → Mode Page de commentaires**, ou
le bouton **Commentaires** en bas de la fenêtre. En présentation, elles
apparaissent dans le **mode Présentateur** sur l'écran de l'orateur seulement.

La dernière diapositive porte en note les **questions probables** et l'endroit
du jeu où trouver la réponse.

### Si le contenu doit changer

Modifier `engendrer-presentation.mjs`, puis :

```bash
cd docs/presentation
node engendrer-presentation.mjs
```

> ⚠️ **Fermer PowerPoint avant.** Un fichier ouvert est verrouillé par
> Windows : la génération échoue avec une erreur d'accès, et un fichier
> `~$GARAH-presentation.pptx` traîne à côté.

Le script a besoin de `pptxgenjs`. Il n'est **pas** installé dans le dépôt —
c'est un outil de rédaction, pas une dépendance du produit :

```bash
npm install pptxgenjs
```

### Pour une présentation courte

Les parties 1, 2, 7, 8 et 10 tiennent debout seules — une quinzaine de
diapositives : le problème, la réponse, ce qui distingue, où en est le projet,
ce qu'un partenariat accélérerait.

---

## Le dossier — Overleaf

1. Sur [overleaf.com](https://www.overleaf.com) : **New Project → Blank
   Project**, puis coller le contenu de `garah-dossier.tex`.
2. Vérifier que le compilateur est **pdfLaTeX** (*Menu → Compiler*). C'est le
   réglage par défaut.
3. **Recompile**.

Le document est autonome : aucun fichier annexe, et uniquement des paquets
présents dans Overleaf.

> ⚠️ La date de couverture est `\today` — elle se met à jour à chaque
> compilation. Pour la figer avant un envoi, remplacer `\today` par la date
> voulue.

### Ce qu'on voudra sans doute changer

| Où | Quoi |
|---|---|
| Page de couverture | Le logo : `\includegraphics[width=4cm]{logo.png}` après le `\vspace*{3cm}` |
| `\definecolor{marque}` | Le vert de la marque, si la charte évolue |
| Section 5 | Les chiffres, quand le projet avance |
| Section 7 | Ce qu'on demande au partenaire — à adapter à l'interlocuteur |
