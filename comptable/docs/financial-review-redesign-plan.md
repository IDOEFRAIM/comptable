# Redesign du dossier financier — plan d'écran (wireframe textuel)

```
┌ Dossier financier                                   [Exercice ▾]  Recalculer  Prévisualiser  [Finaliser] ┐
│ Société ABC · Exercice 2026                                                                              │
│ ● Pré-finalisation · 3 points bloquants · 2 informations à compléter                                     │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Vue d'ensemble · Compte de résultat · Bilan · TFT · Notes annexes 42/46 · Contrôles 5 · Version finale   │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Vue d'ensemble : une ligne (CR ✓ | Bilan ✓ | TFT Incomplet | Notes 42/46 | Exercice Ouvert), sans cadres  │
│ À traiter · 5   (cause · impact · action ›)  — une ligne par exception, filets fins                        │
│ États : tableau Code | Libellé | Brut | Amort. | Net N | Net N-1, totaux en gras avec filet, menu « ⋯ »   │
│ Notes : maître (liste, statut discret) | détail (calculé / complémentaire / revue, Enregistrer + Revue)    │
│ Contrôles : inbox Bloquants / Avertissements / Résolus, fine barre + icône rouges, texte anthracite       │
│ Version finale : checklist compacte, puis « Finaliser le dossier » ; snapshot = document financier       │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
Principes : un seul statut dominant ; rouge/orange/vert réservés aux statuts ; structure par espacement et filets ; une action primaire par zone.
