import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, finalize, map } from 'rxjs/operators';
import { EntrepriseService } from '../entreprises/entreprise.service';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { StatusComponent } from '../../shared/ui/status.component';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { ExerciceComptableDto } from '../comptabilite/models/comptabilite.model';
import { BilanDto, EspaceNotesDto, EtatDto, SyntheseDossierDto, TypeEtat } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { OngletControlesComponent } from './onglet-controles.component';
import { OngletEtatComponent } from './onglet-etat.component';
import { OngletFinalisationComponent, ouvrirHtml } from './onglet-finalisation.component';
import { OngletNotesComponent } from './onglet-notes.component';
import { OngletPourquoiComponent } from './onglet-pourquoi.component';

type Onglet = 'VUE_ENSEMBLE' | 'COMPTE_RESULTAT' | 'BILAN' | 'TFT' | 'NOTES' | 'CONTROLES' | 'POURQUOI' | 'VERSION_FINALE';

const ONGLETS: { code: Onglet; libelle: string }[] = [
  { code: 'VUE_ENSEMBLE', libelle: 'Vue d’ensemble' },
  { code: 'COMPTE_RESULTAT', libelle: 'Compte de résultat' },
  { code: 'BILAN', libelle: 'Bilan' },
  { code: 'TFT', libelle: 'TFT' },
  { code: 'NOTES', libelle: 'Notes annexes' },
  { code: 'CONTROLES', libelle: 'Contrôles' },
  { code: 'VERSION_FINALE', libelle: 'Version finale' },
];

/**
 * Dossier financier d'un exercice : vue d'ensemble, états, notes, contrôles, pré-finalisation et version finale. Les statuts et la readiness viennent du backend
 * ({@code FinancialDossierService}) : l'écran ne recalcule aucune règle et ne transforme jamais un INCOMPLETE en simple avertissement.
 */
@Component({
  selector: 'app-dossier-financier',
  standalone: true,
  imports: [
    FormsModule,
    StatusComponent,
    IconComponent,
    EmptyStateComponent,
    SectionHeaderComponent,
    OngletEtatComponent,
    OngletPourquoiComponent,
    OngletNotesComponent,
    OngletControlesComponent,
    OngletFinalisationComponent,
  ],
  templateUrl: './dossier-financier.component.html',
  styleUrl: './dossier-financier.component.scss',
})
export class DossierFinancierComponent implements OnInit {
  private readonly service = inject(DossierFinancierService);
  private readonly comptabilite = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);
  private readonly entreprises = inject(EntrepriseService);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), { initialValue: '' });

  readonly onglets = ONGLETS;
  readonly nomEntreprise = signal<string | null>(null);
  readonly onglet = signal<Onglet>('VUE_ENSEMBLE');
  readonly exercices = signal<ExerciceComptableDto[]>([]);
  exerciceSelectionne = '';

  readonly synthese = signal<SyntheseDossierDto | null>(null);
  readonly compteResultat = signal<EtatDto | null>(null);
  readonly bilan = signal<BilanDto | null>(null);
  readonly tft = signal<EtatDto | null>(null);
  readonly notes = signal<EspaceNotesDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly selection = signal<{ type: TypeEtat; code: string } | null>(null);
  readonly noteCible = signal<string | null>(null);

  readonly exceptionsBloquantes = computed(() => this.synthese()?.exceptions.filter((e) => e.severity === 'BLOCKING') ?? []);
  readonly interventions = computed(() => (this.synthese()?.exceptions ?? []).slice(0, 3));

  ngOnInit(): void {
    this.entreprises.lister().pipe(catchError(() => of([]))).subscribe((liste) => this.nomEntreprise.set(liste.find((e) => e.id === this.entrepriseId())?.raisonSociale ?? null));
    this.comptabilite.listerExercices(this.entrepriseId()).subscribe({
      next: (exercices) => {
        this.exercices.set(exercices);
        if (exercices.length) {
          this.exerciceSelectionne = exercices[0].id;
          this.charger();
        } else {
          this.erreur.set('Aucun exercice comptable pour cette entreprise.');
        }
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de charger les exercices.'),
    });
  }

  /** Charge les vues agrégées (synthèse, états, notes) en parallèle : une requête par vue, jamais une par ligne ni par note ; le drill-down se fait à la demande. */
  charger(): void {
    if (!this.exerciceSelectionne) {
      return;
    }
    this.chargement.set(true);
    this.erreur.set(null);
    forkJoin({
      synthese: this.service.synthese(this.entrepriseId(), this.exerciceSelectionne),
      compteResultat: this.service.compteResultat(this.entrepriseId(), this.exerciceSelectionne),
      bilan: this.service.bilan(this.entrepriseId(), this.exerciceSelectionne),
      tft: this.service.tft(this.entrepriseId(), this.exerciceSelectionne),
      notes: this.service.notes(this.entrepriseId(), this.exerciceSelectionne),
    })
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (r) => {
          this.synthese.set(r.synthese);
          this.compteResultat.set(r.compteResultat);
          this.bilan.set(r.bilan);
          this.tft.set(r.tft);
          this.notes.set(r.notes);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de charger le dossier financier.'),
      });
  }

  /** Après une action sur une note : seules la synthèse et l'espace des notes changent (les états comptables ne sont pas recalculés ni rechargés). */
  rechargerNotes(): void {
    if (!this.exerciceSelectionne) {
      return;
    }
    forkJoin({
      synthese: this.service.synthese(this.entrepriseId(), this.exerciceSelectionne),
      notes: this.service.notes(this.entrepriseId(), this.exerciceSelectionne),
    }).subscribe({
      next: (r) => {
        this.synthese.set(r.synthese);
        this.notes.set(r.notes);
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de recharger les notes.'),
    });
  }

  /** Après la qualification d'un flux de trésorerie : seuls le TFT et la synthèse (readiness, exceptions) changent ; ni le Compte de résultat, ni le Bilan, ni les notes ne sont rechargés. */
  rechargerFlux(): void {
    if (!this.exerciceSelectionne) {
      return;
    }
    forkJoin({
      synthese: this.service.synthese(this.entrepriseId(), this.exerciceSelectionne),
      tft: this.service.tft(this.entrepriseId(), this.exerciceSelectionne),
    }).subscribe({
      next: (r) => {
        this.synthese.set(r.synthese);
        this.tft.set(r.tft);
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de recharger le TFT.'),
    });
  }

  changerExercice(): void {
    this.selection.set(null);
    this.synthese.set(null);
    this.charger();
  }

  aller(cible: { onglet: string; note?: string }): void {
    const onglet = (ONGLETS.find((o) => o.code === cible.onglet)?.code ?? (cible.onglet === 'BILAN' ? 'BILAN' : 'VUE_ENSEMBLE')) as Onglet;
    if (cible.note) {
      this.noteCible.set(cible.note);
    }
    this.onglet.set(onglet);
  }

  pourquoi(sel: { type: TypeEtat; code: string }): void {
    this.selection.set(sel);
    this.onglet.set('POURQUOI');
  }

  apercu(): void {
    this.service.exporterBrouillon(this.entrepriseId(), this.exerciceSelectionne).subscribe({
      next: (html) => ouvrirHtml(html),
      error: (err) => this.erreur.set(err.error?.message ?? 'Prévisualisation impossible.'),
    });
  }

  /** Exercice affiché dans l'en-tête : l'année de fin (ou « début → fin » si l'exercice ne correspond pas à une année civile). */
  libelleExercice(): string {
    const s = this.synthese();
    if (!s) {
      return '';
    }
    return s.debut.slice(0, 4) === s.fin.slice(0, 4) ? s.fin.slice(0, 4) : `${s.debut} → ${s.fin}`;
  }

  /** Statut global unique du dossier, formulé à partir des données du backend (finalizable, exceptions, checklist) : rien n'est recalculé ici. */
  statutGlobal(s: SyntheseDossierDto): { statut: string; texte: string } {
    if (s.finalised) {
      return { statut: 'FINAL', texte: 'Dossier final' };
    }
    if (s.finalizable) {
      return { statut: 'READY', texte: 'Prêt à finaliser' };
    }
    const bloquants = s.exceptions.filter((e) => e.severity === 'BLOCKING').length;
    const infos = s.exceptions.length - bloquants;
    const parts = ['Pré-finalisation'];
    if (bloquants > 0) {
      parts.push(`${bloquants} point${bloquants > 1 ? 's' : ''} bloquant${bloquants > 1 ? 's' : ''}`);
    } else {
      const motif = s.checklist.find((p) => p.status === 'BLOCKED');
      parts.push(motif ? motif.label : 'finalisation bloquée');
    }
    if (infos > 0) {
      parts.push(`${infos} information${infos > 1 ? 's' : ''} à compléter`);
    }
    return { statut: 'BLOCKING', texte: parts.join(' · ') };
  }

  /** Compteur discret des onglets : progression pour les notes, nombre d'éléments à traiter pour les contrôles ; rien ailleurs. */
  compteurOnglet(code: Onglet): string {
    const s = this.synthese();
    if (!s) {
      return '';
    }
    if (code === 'NOTES') {
      return `${s.notes.decided}/${s.notes.total}`;
    }
    return code === 'CONTROLES' && s.exceptions.length > 0 ? String(s.exceptions.length) : '';
  }

  libelleOnglet(tab: string): string {
    return ONGLETS.find((o) => o.code === tab)?.libelle ?? 'Dossier';
  }
}
