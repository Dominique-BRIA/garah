# Présentation aux partenaires

Deux documents, un seul contenu, deux usages.

| Fichier | Quoi | Comment s'en servir |
|---|---|---|
| `garah-dossier.tex` | Le dossier écrit, ~12 pages | À compiler sur Overleaf |
| `garah-slides.md` | La source des diapositives | À modifier si le contenu change |
| `GARAH-presentation.pptx` | 36 diapositives | À ouvrir dans PowerPoint |

---

## Le dossier — Overleaf

1. Sur [overleaf.com](https://www.overleaf.com), **New Project → Upload Project**,
   ou **New Project → Blank Project** puis coller le contenu de `garah-dossier.tex`.
2. Vérifier que le compilateur est **pdfLaTeX** (menu *Menu → Compiler*).
   C'est le réglage par défaut.
3. **Recompile**.

Aucun fichier annexe n'est nécessaire : le document est autonome et n'utilise
que des paquets présents dans Overleaf.

> ⚠️ La date de couverture est `\today` : elle se met à jour toute seule à
> chaque compilation. Pour la figer avant un envoi, remplacer `\today` par la
> date voulue.

### Ce qu'on voudra sans doute changer

| Où | Quoi |
|---|---|
| Page de couverture | Ajouter le logo — `\includegraphics[width=4cm]{logo.png}` après le `\vspace*{3cm}` |
| `\definecolor{marque}` | Le vert de la marque, si la charte évolue |
| Section 5 | Les chiffres, quand le projet avance |
| Section 7 | Ce qu'on demande au partenaire — à adapter selon l'interlocuteur |

---

## Les diapositives — PowerPoint

Le `.pptx` s'ouvre directement. Il est volontairement **sans habillage** :
PowerPoint applique le thème d'un clic, et le résultat sera meilleur avec la
charte de l'entreprise qu'avec un thème générique.

**Onglet Création → choisir un thème.** Tout le contenu suit.

### Si le contenu doit changer

Modifier `garah-slides.md`, puis régénérer :

```bash
pandoc garah-slides.md -o GARAH-presentation.pptx --slide-level=2
```

Pour partir d'un modèle d'entreprise existant :

```bash
pandoc garah-slides.md -o GARAH-presentation.pptx --slide-level=2 \
       --reference-doc=modele-entreprise.pptx
```

### La structure

Neuf parties, chacune ouverte par une diapositive de titre :

1. Le problème
2. La réponse
3. Vendre
4. Servir
5. Acheminer
6. Compter
7. Ce qui distingue GARAH
8. Où en est le projet
9. Les fondations, puis La suite

36 diapositives, c'est prévu **large**. Pour une présentation courte, garder
les parties 1, 2, 7, 8 et 9 — soit une quinzaine de diapositives.

---

## Le parti pris de rédaction

Les deux documents s'adressent à des partenaires **non informaticiens**.

- Le sujet est le **commerce**, pas la technologie.
- La technique n'apparaît qu'en preuve de sérieux (section 6 du dossier), et
  elle est réduite au minimum.
- Chaque choix technique est traduit en **conséquence pour l'entreprise** :
  « 345 contrôles automatiques » n'est pas un chiffre de développeur, c'est
  la raison pour laquelle le coût de maintenance ne va pas exploser.
- Les chiffres avancés sont **vérifiables dans le dépôt** : 64 tables,
  188 autorisations, 345 tests, 31 décisions documentées.
