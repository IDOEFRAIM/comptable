import { Component, OnInit, inject, signal } from '@angular/core';
import { NgClass } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { CompteComptableDto } from '../models/comptabilite.model';
import {
  AccountPurpose,
  AccountingAccountMappingDto,
  CLASSE_ATTENDUE_PURPOSE,
  GROUPES_PURPOSE,
  LIBELLES_PURPOSE,
  ResolvedAccountDto,
  TestResolutionRequest,
} from '../models/parametrage-comptable.model';

const TOUS_LES_PURPOSES: AccountPurpose[] = GROUPES_PURPOSE.flatMap((g) => g.purposes);

/** Paramétrage comptable par entreprise (mission Étape 6) : configure, pour chaque purpose, le
 * compte PCE à utiliser en priorité (mapping générique, sans contexte) - toujours un sélecteur
 * parmi les comptes réels de l'entreprise, jamais un numéro saisi librement. Le statut affiché par
 * purpose vient d'un appel réel à test-resolution (même moteur que les opérations), pas d'une
 * déduction locale. */
@Component({
  selector: 'app-parametres-comptables',
  standalone: true,
  imports: [FormsModule, NgClass],
  templateUrl: './parametres-comptables.component.html',
  styleUrl: './parametres-comptables.component.scss',
})
export class ParametresComptablesComponent implements OnInit {
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly groupes = GROUPES_PURPOSE;
  readonly libelles = LIBELLES_PURPOSE;
  readonly classeAttendue = CLASSE_ATTENDUE_PURPOSE;

  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly succes = signal<string | null>(null);
  readonly comptes = signal<CompteComptableDto[]>([]);
  readonly mappingsParPurpose = signal<Map<AccountPurpose, AccountingAccountMappingDto>>(new Map());
  readonly resolutionsParPurpose = signal<Map<AccountPurpose, ResolvedAccountDto>>(new Map());
  readonly selectionParPurpose = signal<Map<AccountPurpose, string>>(new Map());
  readonly enregistrementEnCours = signal<AccountPurpose | null>(null);

  readonly testPurpose = signal<AccountPurpose>('ACHAT_MARCHANDISE');
  testNatureOperation = '';
  testContextKey = '';
  readonly testEnCours = signal(false);
  readonly resultatTest = signal<ResolvedAccountDto | null>(null);

  ngOnInit(): void {
    this.rafraichir();
  }

  comptesPour(purpose: AccountPurpose): CompteComptableDto[] {
    const classe = String(this.classeAttendue[purpose]);
    return this.comptes().filter((c) => c.numero.startsWith(classe) && c.actif);
  }

  mappingPour(purpose: AccountPurpose): AccountingAccountMappingDto | undefined {
    return this.mappingsParPurpose().get(purpose);
  }

  resolutionPour(purpose: AccountPurpose): ResolvedAccountDto | undefined {
    return this.resolutionsParPurpose().get(purpose);
  }

  selectionPour(purpose: AccountPurpose): string {
    return this.selectionParPurpose().get(purpose) ?? this.mappingPour(purpose)?.compteId ?? '';
  }

  changerSelection(purpose: AccountPurpose, compteId: string): void {
    this.selectionParPurpose.update((m) => new Map(m).set(purpose, compteId));
  }

  aUneModificationEnAttente(purpose: AccountPurpose): boolean {
    const selection = this.selectionParPurpose().get(purpose);
    if (selection === undefined) {
      return false;
    }
    return selection !== (this.mappingPour(purpose)?.compteId ?? '');
  }

  badgeStatut(purpose: AccountPurpose): { texte: string; classe: string } {
    const resolution = this.resolutionPour(purpose);
    if (!resolution) {
      return { texte: '…', classe: 'badge-attente' };
    }
    switch (resolution.status) {
      case 'RESOLVED':
        return { texte: '✓ Résolu', classe: 'badge-resolu' };
      case 'AMBIGUOUS':
        return { texte: '🔴 Ambigu', classe: 'badge-ambigu' };
      case 'INVALID':
        return { texte: '⛔ Invalide', classe: 'badge-ambigu' };
      default:
        return { texte: '⚠ Non configuré', classe: 'badge-non-configure' };
    }
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe({
      next: (comptes) => this.comptes.set(comptes),
      error: () => this.erreur.set('Impossible de charger le plan comptable.'),
    });
    this.comptabiliteService
      .listerAccountingAccountMappings(this.entrepriseId())
      .pipe(finalize(() => this.chargerResolutions()))
      .subscribe({
        next: (mappings) => {
          const parPurpose = new Map<AccountPurpose, AccountingAccountMappingDto>();
          for (const m of mappings) {
            if (m.actif && !m.contextKey) {
              parPurpose.set(m.purpose, m);
            }
          }
          this.mappingsParPurpose.set(parPurpose);
          this.selectionParPurpose.set(new Map());
        },
        error: () => this.erreur.set('Impossible de charger le paramétrage comptable.'),
      });
  }

  private chargerResolutions(): void {
    const appels = TOUS_LES_PURPOSES.map((purpose) =>
      this.comptabiliteService.testerResolutionCompte(this.entrepriseId(), { purpose }).pipe(
        map((resolution) => [purpose, resolution] as const),
        catchError(() => of([purpose, null] as const)),
      ),
    );
    forkJoin(appels)
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe((resultats) => {
        const parPurpose = new Map<AccountPurpose, ResolvedAccountDto>();
        for (const [purpose, resolution] of resultats) {
          if (resolution) {
            parPurpose.set(purpose, resolution);
          }
        }
        this.resolutionsParPurpose.set(parPurpose);
      });
  }

  enregistrer(purpose: AccountPurpose): void {
    const compteId = this.selectionPour(purpose);
    if (!compteId) {
      return;
    }
    this.enregistrementEnCours.set(purpose);
    this.erreur.set(null);
    this.succes.set(null);
    const mappingExistant = this.mappingPour(purpose);
    const appel = mappingExistant
      ? this.comptabiliteService.modifierAccountingAccountMapping(this.entrepriseId(), mappingExistant.id, { compteId })
      : this.comptabiliteService.creerAccountingAccountMapping(this.entrepriseId(), { purpose, compteId });
    appel.pipe(finalize(() => this.enregistrementEnCours.set(null))).subscribe({
      next: () => {
        this.succes.set(`Paramétrage enregistré pour ${this.libelles[purpose]}.`);
        this.rafraichir();
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible d\'enregistrer ce paramétrage.'),
    });
  }

  retirer(purpose: AccountPurpose): void {
    const mapping = this.mappingPour(purpose);
    if (!mapping) {
      return;
    }
    this.enregistrementEnCours.set(purpose);
    this.erreur.set(null);
    this.comptabiliteService
      .supprimerAccountingAccountMapping(this.entrepriseId(), mapping.id)
      .pipe(finalize(() => this.enregistrementEnCours.set(null)))
      .subscribe({
        next: () => this.rafraichir(),
        error: () => this.erreur.set('Impossible de retirer ce paramétrage.'),
      });
  }

  tester(): void {
    this.testEnCours.set(true);
    this.resultatTest.set(null);
    const request: TestResolutionRequest = {
      purpose: this.testPurpose(),
      natureOperation: this.testNatureOperation || undefined,
      contextKey: this.testContextKey || undefined,
    };
    this.comptabiliteService
      .testerResolutionCompte(this.entrepriseId(), request)
      .pipe(finalize(() => this.testEnCours.set(false)))
      .subscribe({
        next: (resultat) => this.resultatTest.set(resultat),
        error: () => this.erreur.set('Impossible de tester cette résolution.'),
      });
  }
}
