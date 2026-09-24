import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { CompteComptableDto } from '../models/comptabilite.model';
import {
  CompteComptableArbreDto,
  ImporterReferenceResultDto,
  PlanComptableReferenceDto,
} from '../models/plan-comptable.model';
import { PlanComptableTreeNodeComponent } from './plan-comptable-tree-node.component';

/** Plan comptable d'une entreprise : arbre + recherche + detail + actions minimales
 * (activer/desactiver, subdivision locale, libelle local, import du referentiel). Volontairement
 * simple - pas d'ecran de comptabilite analytique a ce stade. */
@Component({
  selector: 'app-plan-comptable-page',
  standalone: true,
  imports: [FormsModule, PlanComptableTreeNodeComponent],
  templateUrl: './plan-comptable-page.component.html',
  styleUrl: './plan-comptable-page.component.scss',
})
export class PlanComptablePageComponent implements OnInit {
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly arbre = signal<CompteComptableArbreDto[]>([]);
  readonly comptesParId = signal<Map<string, CompteComptableDto>>(new Map());
  readonly recherche = signal('');
  readonly compteSelectionneId = signal<string | null>(null);

  readonly afficherFormulaireSubdivision = signal(false);
  readonly afficherPanneauImport = signal(false);
  readonly envoiEnCours = signal(false);
  readonly referentielGlobal = signal<PlanComptableReferenceDto[]>([]);
  readonly codesSelectionnes = signal<Set<string>>(new Set());
  readonly resultatImport = signal<string | null>(null);
  readonly avertissementDesactivation = signal<string | null>(null);

  nouveauNumero = '';
  nouvelIntitule = '';
  nouveauLettrable = false;
  intituleEnEdition = '';

  readonly compteSelectionne = computed(() => {
    const id = this.compteSelectionneId();
    return id ? (this.comptesParId().get(id) ?? null) : null;
  });

  readonly resultatsRecherche = computed(() => {
    const terme = this.recherche().trim().toLowerCase();
    if (!terme) {
      return [];
    }
    return [...this.comptesParId().values()]
      .filter((c) => c.numero.toLowerCase().includes(terme) || c.intitule.toLowerCase().includes(terme))
      .sort((a, b) => a.numero.localeCompare(b.numero));
  });

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .arbrePlanComptable(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (arbre) => this.arbre.set(arbre),
        error: () => this.erreur.set('Impossible de charger le plan comptable.'),
      });
    this.comptabiliteService.listerPlanComptable(this.entrepriseId()).subscribe({
      next: (comptes) => this.comptesParId.set(new Map(comptes.map((c) => [c.id, c]))),
      error: () => this.erreur.set('Impossible de charger le plan comptable.'),
    });
  }

  selectionner(id: string): void {
    this.compteSelectionneId.set(id);
    this.intituleEnEdition = this.comptesParId().get(id)?.intitule ?? '';
  }

  basculerActif(): void {
    const compte = this.compteSelectionne();
    if (!compte) {
      return;
    }
    this.avertissementDesactivation.set(null);
    this.comptabiliteService
      .modifierCompte(this.entrepriseId(), compte.id, { actif: !compte.actif })
      .subscribe({
        next: (resultat) => {
          if (resultat.mappingsActifsCount) {
            this.avertissementDesactivation.set(
              `Attention : ce compte est utilisé par ${resultat.mappingsActifsCount} paramètre(s) comptable(s). ` +
                `Reconfigurez-les dans Paramètres comptables avant de compter sur les opérations concernées.`,
            );
          }
          this.rafraichir();
        },
        error: () => this.erreur.set('Impossible de modifier ce compte.'),
      });
  }

  enregistrerIntitule(): void {
    const compte = this.compteSelectionne();
    if (!compte || !this.intituleEnEdition.trim()) {
      return;
    }
    this.comptabiliteService
      .modifierCompte(this.entrepriseId(), compte.id, { intitule: this.intituleEnEdition.trim() })
      .subscribe({
        next: () => this.rafraichir(),
        error: () => this.erreur.set('Impossible de modifier ce compte.'),
      });
  }

  creerSubdivision(): void {
    if (!this.nouveauNumero.trim() || !this.nouvelIntitule.trim()) {
      return;
    }
    this.envoiEnCours.set(true);
    this.comptabiliteService
      .creerSubdivisionCompte(this.entrepriseId(), {
        numero: this.nouveauNumero.trim(),
        intitule: this.nouvelIntitule.trim(),
        lettrable: this.nouveauLettrable,
        parentId: this.compteSelectionneId() ?? undefined,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: () => {
          this.nouveauNumero = '';
          this.nouvelIntitule = '';
          this.nouveauLettrable = false;
          this.afficherFormulaireSubdivision.set(false);
          this.rafraichir();
        },
        error: () => this.erreur.set('Impossible de créer ce compte (numéro déjà utilisé ?).'),
      });
  }

  ouvrirPanneauImport(): void {
    this.afficherPanneauImport.set(true);
    if (this.referentielGlobal().length === 0) {
      this.comptabiliteService.listerReferentielGlobal().subscribe({
        next: (liste) => this.referentielGlobal.set(liste),
        error: () => this.erreur.set('Impossible de charger le référentiel SYSCOHADA.'),
      });
    }
  }

  basculerSelectionCode(numero: string): void {
    this.codesSelectionnes.update((set) => {
      const copie = new Set(set);
      if (copie.has(numero)) {
        copie.delete(numero);
      } else {
        copie.add(numero);
      }
      return copie;
    });
  }

  importerTout(): void {
    this.envoiEnCours.set(true);
    this.comptabiliteService
      .importerReference(this.entrepriseId(), { mode: 'TOUT' })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (resultat) => this.apresImport(resultat),
        error: () => this.erreur.set('Import impossible.'),
      });
  }

  importerSelection(): void {
    const codes = [...this.codesSelectionnes()];
    if (codes.length === 0) {
      return;
    }
    this.envoiEnCours.set(true);
    this.comptabiliteService
      .importerReference(this.entrepriseId(), { mode: 'SELECTION', codes })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (resultat) => this.apresImport(resultat),
        error: () => this.erreur.set('Import impossible.'),
      });
  }

  private apresImport(resultat: ImporterReferenceResultDto): void {
    this.resultatImport.set(
      `${resultat.comptesAjoutes.length} compte(s) ajouté(s)` +
        (resultat.comptesIgnores.length > 0 ? `, ${resultat.comptesIgnores.length} ignoré(s)` : ''),
    );
    this.codesSelectionnes.set(new Set());
    this.afficherPanneauImport.set(false);
    this.rafraichir();
  }
}
