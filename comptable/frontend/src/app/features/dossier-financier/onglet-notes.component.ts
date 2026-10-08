import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { EspaceNotesDto, NoteDto, PatchChamp, SectionNote } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { formaterMontant } from './montant';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { StatusComponent } from '../../shared/ui/status.component';

type Filtre = 'TOUTES' | 'A_DECIDER' | 'A_COMPLETER' | 'PRETES' | 'REVUES' | 'NON_APPLICABLES' | 'BLOQUEES';

const FILTRES: { code: Filtre; libelle: string }[] = [
  { code: 'TOUTES', libelle: 'Toutes' },
  { code: 'A_DECIDER', libelle: 'À décider' },
  { code: 'A_COMPLETER', libelle: 'À compléter' },
  { code: 'PRETES', libelle: 'Prêtes' },
  { code: 'REVUES', libelle: 'Revues' },
  { code: 'NON_APPLICABLES', libelle: 'Non applicables' },
  { code: 'BLOQUEES', libelle: 'Bloquées' },
];

const MESSAGE_CONFLIT = 'Cette note a été modifiée par un autre utilisateur. Rechargez les dernières données avant de poursuivre.';

/**
 * Espace de travail des Notes annexes : filtres, applicabilité (décision explicite, validation en masse avec liste préalable), parties calculées en lecture seule, formulaires générés
 * depuis le schéma sections/champs du référentiel, verrouillage optimiste (expectedVersion), revue. La validation d'autorité est celle du backend.
 */
@Component({
  selector: 'app-onglet-notes',
  standalone: true,
  imports: [FormsModule, StatusComponent, EmptyStateComponent, SectionHeaderComponent],
  template: `
    <div class="notes-layout" data-zone="notes">
      <section class="liste-notes">
        <app-section-header [titre]="'Notes annexes'" [compteur]="espace()?.notes?.length ?? 0">
          @if (espace(); as e) {
            <span class="ui-meta" data-champ="progression-notes">{{ decidees() }} / {{ e.notes.length }} décidées</span>
          }
        </app-section-header>

        @if (candidates().length && permissions().prepare) {
          <div class="ui-notice ui-notice--info bandeau-candidates" data-zone="candidates">
            <div>
              <p class="bandeau__titre">{{ candidates().length }} suggestion(s) de non-applicabilité</p>
              <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="confirmationBatch.set(!confirmationBatch())" data-action="valider-candidates">
                Valider les {{ candidates().length }} suggestions de non-applicabilité
              </button>
              @if (confirmationBatch()) {
                <div class="confirmation" data-zone="confirmation-candidates">
                  <p>Les notes suivantes seront déclarées <strong>non applicables</strong> (décision tracée à votre nom) :</p>
                  <ul>
                    @for (n of candidates(); track n.noteCode) {
                      <li>Note {{ n.noteCode }} — {{ n.title }}</li>
                    }
                  </ul>
                  <button type="button" class="ui-btn ui-btn--primary ui-btn--sm" [disabled]="enCours()" (click)="validerCandidates()" data-action="confirmer-candidates">Confirmer la validation</button>
                  <button type="button" class="ui-btn ui-btn--tertiary ui-btn--sm" (click)="confirmationBatch.set(false)">Annuler</button>
                </div>
              }
            </div>
          </div>
        }

        <div class="filtres-notes" role="group" aria-label="Filtrer les notes">
          @for (f of filtres; track f.code) {
            <button type="button" class="filtre" [class.actif]="filtre() === f.code" (click)="filtre.set(f.code)" [attr.data-filtre]="f.code">
              {{ f.libelle }} <span class="compteur">{{ compte(f.code) }}</span>
            </button>
          }
        </div>

        @if (erreur()) {
          <p class="ui-notice ui-notice--danger">{{ erreur() }}</p>
        }
        <ul class="notes">
          @for (n of notesFiltrees(); track n.noteCode) {
            <li [class.choisie]="n.noteCode === noteChoisie()" (click)="choisir(n.noteCode)" [attr.data-note]="n.noteCode" tabindex="0" (keydown.enter)="choisir(n.noteCode)">
              <span class="note-code">{{ n.noteCode }}</span>
              <div class="note-corps">
                <p class="note-titre">{{ n.title }}</p>
                <p class="note-etat" [class.note-etat--attention]="attention(n)">
                  {{ resume(n) }}
                  @if (n.completionStatus === 'READY') {
                    @if (n.review.valid) {
                      <span class="revue" data-champ="revue-ok"> · Revue</span>
                    } @else if (n.review.reviewedAt === null) {
                      <span class="revue"> · Revue : non</span>
                    }
                  }
                </p>
              </div>
            </li>
          } @empty {
            <li class="vide">Aucune note dans ce filtre.</li>
          }
        </ul>
      </section>

      <section class="detail-note">
        @if (note(); as n) {
          <header class="entete-detail">
            <h3 class="ui-section-title">Note {{ n.noteCode }} — {{ n.title }}</h3>
            <div class="statuts-note">
              <app-status [statut]="n.applicabilityStatus" />
              <app-status [statut]="n.completionStatus" />
              <span class="ui-meta">Type : {{ n.automationType }} · version {{ n.version }}</span>
            </div>
          </header>
          @if (alerte()) {
            <p class="ui-notice ui-notice--danger" data-zone="conflit">{{ alerte() }} <button type="button" class="ui-btn ui-btn--link" (click)="recharger.emit()">Recharger</button></p>
          }
          @if (info()) {
            <p class="ui-notice ui-notice--success">{{ info() }}</p>
          }
          @if (revueObsolete()) {
            <p class="ui-notice" data-champ="revue-obsolete">Revue devenue obsolète : la note a été modifiée après la revue ; une nouvelle revue de l’expert-comptable est nécessaire.</p>
          }

          <!-- Applicabilité -->
          <div class="bloc" data-zone="applicabilite">
            <h4 class="ui-eyebrow">Applicabilité</h4>
            @if (n.noteCode === '35') {
              <p data-champ="note35">
                @if (effectifConnu(n); as eff) {
                  Effectif connu : <strong>{{ eff }}</strong> → {{ n.applicabilityStatus === 'APPLICABLE' ? 'Note obligatoire' : n.applicabilityStatus === 'NOT_APPLICABLE' ? 'Note non obligatoire' : 'Applicabilité à déterminer' }} (règle appliquée par le serveur)
                } @else {
                  Effectif non renseigné → applicabilité à déterminer
                }
              </p>
            }
            @switch (n.applicabilityStatus) {
              @case ('NOT_APPLICABLE_CANDIDATE') {
                <p>Ladini propose : <strong>Non applicable</strong> — absence prouvée par les comptes, à valider explicitement.</p>
              }
              @case ('TO_DETERMINE') {
                <p>Aucune preuve comptable ne permet de conclure : décision humaine requise.</p>
              }
              @case ('NOT_APPLICABLE') {
                <p>Non applicable{{ n.applicabilityDecision.humaine ? ' — décidé' : ' — règle réglementaire' }}
                  @if (n.applicabilityDecision.reason) { : {{ n.applicabilityDecision.reason }} }
                  (cette note n’est pas jointe au dossier final).</p>
              }
              @default {
                <p>Note retenue au dossier.</p>
              }
            }
            @if (permissions().prepare) {
              <div class="decision">
                <div class="ui-field decision__motif">
                  <label for="motif-applicabilite" class="ui-field__label">Motif</label>
                  <input id="motif-applicabilite" class="ui-input" type="text" [ngModel]="motif()" (ngModelChange)="motif.set($event)" data-champ="motif-applicabilite" />
                  <span class="ui-field__hint">Conservé dans la piste d’audit avec la décision.</span>
                </div>
                <div class="decision__actions">
                  @if (n.applicabilityStatus !== 'APPLICABLE' || !n.applicabilityDecision.humaine) {
                    <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="decider(n, 'APPLICABLE')" data-action="applicable">Applicable</button>
                  }
                  @if (n.applicabilityStatus !== 'NOT_APPLICABLE') {
                    <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="decider(n, 'NOT_APPLICABLE')" data-action="non-applicable">Non applicable</button>
                  }
                  @if (n.applicabilityDecision.humaine) {
                    <button type="button" class="ui-btn ui-btn--tertiary ui-btn--sm" (click)="rouvrir(n)" data-action="rouvrir">Rouvrir</button>
                  }
                </div>
              </div>
            }
          </div>

          @if (n.attached) {
            <!-- Données calculées -->
            <div class="bloc" data-zone="donnees-calculees">
              <h4 class="ui-eyebrow">Données calculées</h4>
              @if (n.generatedData.length) {
                <table class="ui-table">
                  <thead><tr><th>Libellé</th><th class="ui-num">N</th><th class="ui-num">N-1</th></tr></thead>
                  <tbody>
                    @for (l of n.generatedData; track l.code) {
                      <tr [class.total]="l.kind !== 'LINE'">
                        <td>{{ l.label }}</td>
                        <td class="ui-num">{{ l.amountN === null ? 'Non déterminé' : montant(l.amountN) }}</td>
                        <td class="ui-num ui-muted">{{ montant(l.amountNMinus1) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
                @if (n.completionStatus === 'READY' && n.automationType === 'AUTOMATIC') {
                  <p class="complete"><app-status statut="OK" texte="Complète" /></p>
                }
              } @else {
                <p class="ui-meta">
                  @if (n.automationType === 'MANUAL_REQUIRED') { Note entièrement manuelle : aucune donnée comptable générée. }
                  @else { Aucune donnée générée pour cette note ({{ n.readiness }}). }
                </p>
              }
              @for (g of generatedGaps(n); track g.section + g.missingInformation) {
                <p class="manque">{{ g.section }} : {{ g.missingInformation }}</p>
              }
            </div>
          }

          <!-- Informations complémentaires -->
          @if (sectionsVisibles(n).length) {
            <div class="bloc" data-zone="informations-a-completer">
              <h4 class="ui-eyebrow">Informations complémentaires</h4>
              @for (s of sectionsVisibles(n); track s.sectionCode) {
                <fieldset class="section" [attr.data-section]="s.sectionCode">
                  <legend>
                    {{ libelle(s.label) }}
                    @if (s.status === 'MISSING') { <span class="etat-section etat-section--manquant">À compléter</span> }
                    @else if (s.status === 'COMPLETE') { <span class="etat-section">Complété</span> }
                    @else { <span class="etat-section">Facultatif</span> }
                  </legend>
                  @for (c of s.fields; track c.fieldCode) {
                    <div class="ui-field">
                      <label class="ui-field__label" [class.ui-sr-only]="s.fields.length === 1 && c.label === s.label" [for]="id(s, c.fieldCode)">{{ libelle(c.label) }} @if (c.required) { <span class="ui-required" aria-hidden="true">*</span> }</label>
                      @switch (c.type) {
                        @case ('BOOLEAN') {
                          <select class="ui-input" [id]="id(s, c.fieldCode)" [ngModel]="brouillon(s, c.fieldCode, c.value)" (ngModelChange)="saisir(s, c.fieldCode, $event)" [disabled]="!editable(n)">
                            <option value="">—</option><option value="true">Oui</option><option value="false">Non</option>
                          </select>
                        }
                        @case ('ENUM') {
                          <select class="ui-input" [id]="id(s, c.fieldCode)" [ngModel]="brouillon(s, c.fieldCode, c.value)" (ngModelChange)="saisir(s, c.fieldCode, $event)" [disabled]="!editable(n)">
                            <option value="">—</option>
                            @for (o of c.options ?? []; track o) { <option [value]="o">{{ o }}</option> }
                          </select>
                        }
                        @case ('INTEGER') {
                          <input class="ui-input" [id]="id(s, c.fieldCode)" type="number" step="1" [ngModel]="brouillon(s, c.fieldCode, c.value)" (ngModelChange)="saisir(s, c.fieldCode, $event)" [disabled]="!editable(n)" />
                        }
                        @case ('DECIMAL') {
                          <input class="ui-input" [id]="id(s, c.fieldCode)" type="text" inputmode="decimal" [ngModel]="brouillon(s, c.fieldCode, c.value)" (ngModelChange)="saisir(s, c.fieldCode, $event)" [disabled]="!editable(n)" />
                        }
                        @case ('DATE') {
                          <input class="ui-input" [id]="id(s, c.fieldCode)" type="date" [ngModel]="brouillon(s, c.fieldCode, c.value)" (ngModelChange)="saisir(s, c.fieldCode, $event)" [disabled]="!editable(n)" />
                        }
                        @default {
                          <textarea class="ui-input" [id]="id(s, c.fieldCode)" rows="3" [ngModel]="brouillon(s, c.fieldCode, c.value)" (ngModelChange)="saisir(s, c.fieldCode, $event)" [disabled]="!editable(n)" [attr.data-champ]="'champ-' + s.sectionCode"></textarea>
                        }
                      }
                      @if (c.required && !c.filled) {
                        <span class="ui-field__hint">Information requise pour que la note soit prête.</span>
                      }
                    </div>
                  }
                </fieldset>
              }
              @if (editable(n)) {
                <button type="button" class="ui-btn ui-btn--primary" [disabled]="enCours() || !modifie()" (click)="enregistrer(n)" data-action="enregistrer-note">Enregistrer</button>
              } @else if (!permissions().prepare) {
                <p class="ui-meta">Lecture seule : la saisie est réservée aux collaborateurs et experts-comptables.</p>
              }
            </div>
          }

          @if (n.attached) {
            <!-- Revue -->
            <div class="bloc" data-zone="revue">
              <h4 class="ui-eyebrow">Revue</h4>
              @if (n.review.valid) {
                <p data-champ="revue-info"><app-status statut="OK" [texte]="'Revue par ' + (n.review.reviewedBy ? 'l’expert-comptable' : '') + ' le ' + n.review.reviewedAt" /></p>
              } @else if (n.completionStatus === 'READY') {
                <p class="ui-meta" data-champ="revue-etat">Note prête, non revue. Une modification après revue la rend obsolète : la revue est alors à refaire.</p>
              } @else {
                <p class="ui-meta">La revue n’est possible qu’une fois la note prête.</p>
              }
              @if (permissions().review && n.completionStatus === 'READY' && !n.review.valid) {
                <button type="button" class="ui-btn ui-btn--secondary" [disabled]="enCours()" (click)="relire(n)" data-action="marquer-revue">Marquer comme revue</button>
              }
            </div>
          }
        } @else {
          <app-empty-state icone="file" titre="Aucune note sélectionnée" detail="Sélectionnez une note pour voir son état, ses données calculées et ce qu’il reste à compléter." />
        }
      </section>
    </div>
  `,
  styles: [
    `
      .notes-layout { display: grid; grid-template-columns: minmax(300px, 380px) 1fr; gap: var(--ui-s6); align-items: start; }
      @media (max-width: 900px) { .notes-layout { grid-template-columns: 1fr; } }
      .bandeau__titre { margin: 0 0 var(--ui-s2); font-weight: var(--ui-fw-medium); }
      .confirmation { margin-top: var(--ui-s3); max-height: 14rem; overflow: auto; font-size: var(--ui-fs-sm); }
      .confirmation ul { margin: var(--ui-s1) 0 var(--ui-s3); padding-left: var(--ui-s5); }
      .filtres-notes { display: flex; flex-wrap: wrap; gap: var(--ui-s1) var(--ui-s3); margin: var(--ui-s3) 0; }
      .filtre { padding: var(--ui-s1) 0; border: 0; border-bottom: 1px solid transparent; background: transparent; color: var(--ui-text-2); font: inherit; font-size: var(--ui-fs-sm); cursor: pointer; }
      .filtre:hover { color: var(--ui-text); }
      .filtre.actif { color: var(--ui-text); font-weight: var(--ui-fw-semibold); border-bottom-color: var(--ui-primary); }
      .compteur { margin-left: 2px; color: var(--ui-text-3); font-variant-numeric: tabular-nums; }
      ul.notes { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--ui-line); }
      ul.notes li { display: grid; grid-template-columns: 28px 1fr; gap: var(--ui-s2); padding: var(--ui-s3) var(--ui-s3); border-bottom: 1px solid var(--ui-line); cursor: pointer; box-shadow: inset 2px 0 0 transparent; }
      ul.notes li:hover { background: var(--ui-surface-subtle); }
      ul.notes li.choisie { background: var(--ui-primary-tint); box-shadow: inset 2px 0 0 var(--ui-primary); }
      ul.notes li.vide { display: block; color: var(--ui-text-3); cursor: default; }
      .note-code { font-family: var(--ui-mono); font-size: var(--ui-fs-xs); color: var(--ui-text-3); padding-top: 2px; }
      .note-titre { margin: 0; font-size: var(--ui-fs-md); font-weight: var(--ui-fw-medium); }
      .note-etat { margin: 2px 0 0; font-size: var(--ui-fs-xs); color: var(--ui-text-2); }
      .note-etat--attention { color: var(--ui-warning); }
      .note-etat .revue { color: var(--ui-text-3); }
      .detail-note { min-width: 0; max-width: 880px; }
      .entete-detail { margin-bottom: var(--ui-s4); }
      .statuts-note { display: flex; gap: var(--ui-s4); flex-wrap: wrap; align-items: center; margin-top: var(--ui-s2); }
      .bloc { margin-top: var(--ui-s5); padding-top: var(--ui-s5); border-top: 1px solid var(--ui-line); }
      .bloc h4 { margin: 0 0 var(--ui-s3); }
      .bloc p { margin: 0 0 var(--ui-s2); }
      .decision { display: flex; align-items: flex-end; gap: var(--ui-s4); flex-wrap: wrap; margin-top: var(--ui-s3); }
      .decision__motif { flex: 1; min-width: 240px; margin: 0; }
      .decision__actions { display: flex; gap: var(--ui-s2); }
      tr.total td { font-weight: var(--ui-fw-semibold); }
      .manque { font-size: var(--ui-fs-sm); color: var(--ui-text-2); margin: var(--ui-s2) 0 0; }
      .complete { margin-top: var(--ui-s3) !important; }
      fieldset.section { border: 0; border-left: 2px solid var(--ui-line); margin: 0 0 var(--ui-s4); padding: 0 0 0 var(--ui-s4); min-width: 0; }
      fieldset.section legend { padding: 0; margin-bottom: var(--ui-s3); font-size: var(--ui-fs-md); font-weight: var(--ui-fw-semibold); }
      .etat-section { margin-left: var(--ui-s2); font-size: var(--ui-fs-xs); font-weight: var(--ui-fw-medium); color: var(--ui-text-3); }
      .etat-section--manquant { color: var(--ui-warning); }
    `,
  ],
})
export class OngletNotesComponent implements OnInit {
  private readonly service = inject(DossierFinancierService);

  readonly entrepriseId = input.required<string>();
  readonly exerciceId = input.required<string>();
  readonly espace = input.required<EspaceNotesDto | null>();
  readonly permissions = input.required<{ prepare: boolean; review: boolean; finalise: boolean }>();
  readonly noteInitiale = input<string | null>(null);
  readonly recharger = output<void>();
  readonly modifiee = output<void>();

  readonly filtres = FILTRES;
  readonly filtre = signal<Filtre>('TOUTES');
  readonly noteChoisie = signal<string | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly alerte = signal<string | null>(null);
  readonly info = signal<string | null>(null);
  readonly enCours = signal(false);
  readonly motif = signal('Sans objet pour cette entité');
  /** Vrai juste après une action qui a annulé une revue : « ⚠ Revue devenue obsolète » (jamais une coche verte trompeuse). */
  readonly revueObsolete = signal(false);
  readonly confirmationBatch = signal(false);
  /** Brouillon de saisie : valeurs modifiées localement, envoyées uniquement à l'enregistrement. */
  readonly saisies = signal<Record<string, string>>({});
  readonly modifie = computed(() => Object.keys(this.saisies()).length > 0);

  readonly note = computed<NoteDto | null>(() => this.espace()?.notes.find((n) => n.noteCode === this.noteChoisie()) ?? null);
  readonly candidates = computed(() => (this.espace()?.notes ?? []).filter((n) => n.applicabilityStatus === 'NOT_APPLICABLE_CANDIDATE'));
  readonly decidees = computed(() => (this.espace()?.notes ?? []).filter((n) => n.applicabilityStatus === 'APPLICABLE' || n.applicabilityStatus === 'NOT_APPLICABLE').length);
  readonly notesFiltrees = computed(() => (this.espace()?.notes ?? []).filter((n) => this.correspond(n, this.filtre())));

  montant = formaterMontant;

  ngOnInit(): void {
    const initiale = this.noteInitiale();
    if (initiale) {
      this.noteChoisie.set(initiale);
    }
  }

  /** Résumé d'état d'une ligne de la liste : « À compléter · 2 champs », « Prêt », « Non applicable »… (texte brut, sans badge). */
  resume(n: NoteDto): string {
    switch (n.applicabilityStatus) {
      case 'NOT_APPLICABLE':
        return 'Non applicable';
      case 'TO_DETERMINE':
        return 'À décider';
      case 'NOT_APPLICABLE_CANDIDATE':
        return 'Non applicable proposé';
      default:
        break;
    }
    if (n.completionStatus === 'READY') {
      return 'Prêt';
    }
    const manquants = n.manualSections.reduce((total, s) => total + s.fields.filter((f) => f.required && !f.filled).length, 0);
    return manquants > 0 ? `À compléter · ${manquants} champ${manquants > 1 ? 's' : ''}` : 'À compléter';
  }

  /** Libellé lisible d'un champ ou d'une section dont le référentiel ne fournit qu'un code (« regles_et_methodes » → « Regles et methodes »). */
  libelle(texte: string): string {
    const t = texte.replace(/_/g, ' ').trim();
    return t.charAt(0).toUpperCase() + t.slice(1);
  }

  attention(n: NoteDto): boolean {
    return n.applicabilityStatus === 'TO_DETERMINE' || n.applicabilityStatus === 'NOT_APPLICABLE_CANDIDATE' || (n.applicabilityStatus === 'APPLICABLE' && n.completionStatus !== 'READY');
  }

  compte(f: Filtre): number {
    return (this.espace()?.notes ?? []).filter((n) => this.correspond(n, f)).length;
  }

  private correspond(n: NoteDto, f: Filtre): boolean {
    switch (f) {
      case 'A_DECIDER':
        return n.applicabilityStatus === 'TO_DETERMINE' || n.applicabilityStatus === 'NOT_APPLICABLE_CANDIDATE';
      case 'A_COMPLETER':
        return n.attached && (n.completionStatus === 'INCOMPLETE' || n.completionStatus === 'MANUAL_REQUIRED');
      case 'PRETES':
        return n.completionStatus === 'READY';
      case 'REVUES':
        return n.review.valid;
      case 'NON_APPLICABLES':
        return n.applicabilityStatus === 'NOT_APPLICABLE';
      case 'BLOQUEES':
        return n.attached && n.schemaStatus === 'PARTIAL';
      default:
        return true;
    }
  }

  choisir(code: string): void {
    this.noteChoisie.set(code);
    this.saisies.set({});
    this.alerte.set(null);
    this.info.set(null);
    this.revueObsolete.set(false);
  }

  editable(n: NoteDto): boolean {
    return this.permissions().prepare && n.applicabilityStatus !== 'NOT_APPLICABLE';
  }

  id(s: SectionNote, champ: string): string {
    return `champ-${s.sectionCode}-${champ}`;
  }

  brouillon(s: SectionNote, champ: string, valeur: string | null): string {
    const cle = `${s.sectionCode}|${champ}`;
    const saisies = this.saisies();
    return cle in saisies ? saisies[cle] : (valeur ?? '');
  }

  saisir(s: SectionNote, champ: string, valeur: unknown): void {
    this.saisies.update((m) => ({ ...m, [`${s.sectionCode}|${champ}`]: valeur === null || valeur === undefined ? '' : String(valeur) }));
  }

  effectifConnu(n: NoteDto): string | null {
    const champ = n.manualSections.find((s) => s.sectionCode === 'effectif')?.fields.find((f) => f.fieldCode === 'effectif');
    return champ?.filled ? champ.value : null;
  }

  /**
   * Sections saisissables affichées : toutes pour une note jointe ; pour une note non jointe, seule la section pilotant l'applicabilité (effectif de la Note 35), sans laquelle
   * l'applicabilité réglementaire ne pourrait jamais être déterminée.
   */
  sectionsVisibles(n: NoteDto): SectionNote[] {
    return n.attached ? n.manualSections : n.manualSections.filter((s) => s.sectionCode === 'effectif');
  }

  /** Manques de calcul (hors saisie manuelle) à afficher dans la partie « calculée ». */
  generatedGaps(n: NoteDto) {
    return n.gaps.filter((g) => g.kind === 'COMPUTED' || g.kind === 'SCHEMA');
  }

  enregistrer(n: NoteDto): void {
    const fields: PatchChamp[] = Object.entries(this.saisies()).map(([cle, value]) => {
      const [sectionCode, fieldCode] = cle.split('|');
      return { sectionCode, fieldCode, value: value === '' ? null : value, source: 'saisie' };
    });
    this.executer(this.service.modifierDonneesManuelles(this.entrepriseId(), this.exerciceId(), n.noteCode, n.version, fields), 'Informations enregistrées.', true, n.review.valid);
  }

  decider(n: NoteDto, statut: 'APPLICABLE' | 'NOT_APPLICABLE'): void {
    const raison = statut === 'NOT_APPLICABLE' ? this.motif().trim() : '';
    if (statut === 'NOT_APPLICABLE' && !raison) {
      this.alerte.set('Indiquez le motif de la non-applicabilité (il est tracé avec votre nom).');
      return;
    }
    this.executer(this.service.modifierApplicabilite(this.entrepriseId(), this.exerciceId(), n.noteCode, n.version, statut, raison || undefined), 'Applicabilité enregistrée.', false, n.review.valid);
  }

  rouvrir(n: NoteDto): void {
    this.executer(this.service.rouvrir(this.entrepriseId(), this.exerciceId(), n.noteCode, n.version), 'Note rouverte.', false, n.review.valid);
  }

  relire(n: NoteDto): void {
    this.executer(this.service.relire(this.entrepriseId(), this.exerciceId(), n.noteCode, n.version), 'Note marquée comme revue.', false);
  }

  validerCandidates(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.service
      .validerCandidates(this.entrepriseId(), this.exerciceId())
      .pipe(finalize(() => this.enCours.set(false)))
      .subscribe({
        next: () => {
          this.confirmationBatch.set(false);
          this.info.set('Non-applicabilités validées.');
          this.modifiee.emit();
        },
        error: (err) => this.erreur.set(this.messageErreur(err)),
      });
  }

  private executer(appel: import('rxjs').Observable<unknown>, succes: string, viderSaisies: boolean, etaitRevue = false): void {
    this.enCours.set(true);
    this.alerte.set(null);
    this.info.set(null);
    appel.pipe(finalize(() => this.enCours.set(false))).subscribe({
      next: () => {
        if (viderSaisies) {
          this.saisies.set({});
        }
        this.info.set(succes);
        this.revueObsolete.set(etaitRevue);
        this.modifiee.emit();
      },
      error: (err) => this.alerte.set(this.messageErreur(err)),
    });
  }

  private messageErreur(err: { status?: number; error?: { message?: string } }): string {
    if (err.status === 409 && (err.error?.message ?? '').toLowerCase().includes('version')) {
      return MESSAGE_CONFLIT;
    }
    if (err.status === 409 && (err.error?.message ?? '').toLowerCase().includes('modifi')) {
      return MESSAGE_CONFLIT;
    }
    return err.error?.message ?? 'Action impossible.';
  }
}
