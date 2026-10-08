import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { EtatDto, SnapshotDto } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { formaterMontant } from './montant';
import { OngletEtatComponent } from './onglet-etat.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { StatusComponent } from '../../shared/ui/status.component';

/**
 * Version finale en lecture seule : lit UNIQUEMENT le snapshot immuable (jamais les états vivants). Cadre réglementaire, version, empreintes, exercice, états figés, notes jointes
 * (données générées et manuelles copiées), notes non applicables tracées et contrôles.
 */
@Component({
  selector: 'app-snapshot-viewer',
  standalone: true,
  imports: [OngletEtatComponent, StatusComponent, EmptyStateComponent, SectionHeaderComponent],
  template: `
    @if (erreur()) {
      <p class="ui-notice ui-notice--danger">{{ erreur() }}</p>
    }
    @if (snapshot(); as s) {
      <article class="document">
        <header class="document__entete" data-zone="snapshot-viewer">
          <p class="ui-eyebrow">Dossier financier final</p>
          <h2 class="ui-page-title">{{ s.payload.entreprise?.raisonSociale }}</h2>
          <p class="exercice">Exercice {{ s.payload.exercice?.debut }} → {{ s.payload.exercice?.fin }}</p>
          <dl class="infos">
            <div><dt>Version</dt><dd>{{ s.payload.frameworkVersion }}</dd></div>
            <div><dt>Généré le</dt><dd>{{ s.payload.generatedAt }}</dd></div>
            <div><dt>Finalisé le</dt><dd>{{ s.createdAt }}</dd></div>
            <div class="infos__hash"><dt>Empreinte</dt><dd class="ui-mono" data-champ="snapshot-hash">{{ s.payloadSha256 }}</dd></div>
          </dl>
          <details class="empreintes">
            <summary>Empreintes des définitions réglementaires</summary>
            <ul class="hashes">
              @for (h of hashes(); track h.cle) {
                <li><strong>{{ h.cle }}</strong> <span class="ui-mono">{{ h.valeur }}</span></li>
              }
            </ul>
          </details>
        </header>

        <app-onglet-etat [etat]="etat('compteResultat')" mode="CR" titre="Compte de résultat" [interactif]="false" />
        <app-onglet-etat [etat]="etat('bilanActif')" mode="ACTIF" titre="Bilan — Actif" [interactif]="false" />
        <app-onglet-etat [etat]="etat('bilanPassif')" mode="PASSIF" titre="Bilan — Passif" [interactif]="false" />
        <app-onglet-etat [etat]="etat('tft')" mode="TFT" titre="Tableau des flux de trésorerie" [interactif]="false" />

        <section class="bloc" data-zone="snapshot-notes">
          <app-section-header titre="Notes annexes jointes" [compteur]="s.payload.notes?.notes?.length ?? 0" />
          <p class="ui-sr-only">Notes annexes jointes ({{ s.payload.notes?.notes?.length ?? 0 }})</p>
          @for (n of s.payload.notes?.notes ?? []; track n.noteCode) {
            <div class="note">
              <h4 class="note__titre">Note {{ n.noteCode }} — {{ n.title }} <app-status [statut]="n.completionStatus" /></h4>
              @if (n.generatedData?.length) {
                <table class="ui-table">
                  <thead><tr><th>Libellé</th><th class="ui-num">N</th><th class="ui-num">N-1</th></tr></thead>
                  <tbody>
                    @for (l of n.generatedData; track l.code) {
                      <tr><td>{{ l.label }}</td><td class="ui-num">{{ montant(l.amountN) }}</td><td class="ui-num ui-muted">{{ montant(l.amountNMinus1) }}</td></tr>
                    }
                  </tbody>
                </table>
              }
              @for (sec of sections(n.manualData); track sec.cle) {
                <p class="manuel"><span class="ui-meta">{{ libelle(sec.cle) }}</span><br />{{ sec.valeur }}</p>
              }
              @if (n.review?.reviewedAt) {
                <p class="ui-meta">Revue le {{ n.review.reviewedAt }}</p>
              }
            </div>
          } @empty {
            <app-empty-state icone="file" titre="Aucune note jointe" detail="Aucune note applicable n’est jointe à ce dossier." />
          }
          <details class="empreintes">
            <summary>Notes non applicables (traçabilité, non jointes aux états publiés) — {{ s.payload.notes?.notesNonApplicables?.length ?? 0 }}</summary>
            <ul>
              @for (n of s.payload.notes?.notesNonApplicables ?? []; track n.noteCode) {
                <li>Note {{ n.noteCode }} — {{ n.decision }}{{ n.reason ? ' : ' + n.reason : '' }} ({{ n.decidedAt }})</li>
              }
            </ul>
          </details>
        </section>

        <section class="bloc">
          <app-section-header titre="Contrôles figés" [compteur]="s.payload.controls?.length ?? 0" />
          @if (s.payload.controls?.length) {
            <ul class="controles">
              @for (c of s.payload.controls; track $index) {
                <li><span class="ui-mono">{{ c.code }}</span> {{ c.message }}</li>
              }
            </ul>
          } @else {
            <p class="ui-meta">Aucun contrôle ouvert au moment de la finalisation.</p>
          }
        </section>
      </article>
    }
  `,
  styles: [
    `
      .document { margin-top: var(--ui-s6); padding-top: var(--ui-s6); border-top: 1px solid var(--ui-line-strong); }
      .document__entete { margin-bottom: var(--ui-s6); }
      .document__entete .ui-eyebrow { text-transform: none; letter-spacing: 0; font-size: var(--ui-fs-sm); }
      .document__entete h2 { margin-top: var(--ui-s1); }
      .exercice { margin: var(--ui-s1) 0 var(--ui-s5); font-size: var(--ui-fs-lg); color: var(--ui-text-2); }
      dl.infos { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--ui-s3) var(--ui-s5); margin: 0 0 var(--ui-s3); }
      dl.infos dd { overflow-wrap: anywhere; }
      .infos__hash { grid-column: 1 / -1; }
      dl.infos dt { font-size: var(--ui-fs-xs); color: var(--ui-text-3); }
      dl.infos dd { margin: 0; font-weight: var(--ui-fw-medium); }
      .empreintes { margin-top: var(--ui-s3); font-size: var(--ui-fs-sm); color: var(--ui-text-2); }
      .empreintes summary { cursor: pointer; }
      ul.hashes { margin: var(--ui-s2) 0; padding-left: var(--ui-s5); }
      .bloc { margin-bottom: var(--ui-s6); }
      .note { padding: var(--ui-s4) 0; border-top: 1px solid var(--ui-line); }
      .note__titre { display: flex; gap: var(--ui-s3); align-items: center; margin: 0 0 var(--ui-s2); font-size: var(--ui-fs-md); font-weight: var(--ui-fw-semibold); }
      .manuel { margin: var(--ui-s2) 0 0; }
      ul.controles { margin: 0; padding-left: var(--ui-s5); }
    `,
  ],
})
export class SnapshotViewerComponent {
  private readonly service = inject(DossierFinancierService);

  readonly entrepriseId = input.required<string>();
  readonly snapshotId = input.required<string>();

  readonly snapshot = signal<SnapshotDto | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly hashes = computed(() => Object.entries(this.snapshot()?.payload?.definitionHashes ?? {}).map(([cle, valeur]) => ({ cle, valeur: String(valeur) })));

  montant = formaterMontant;

  constructor() {
    effect(() => {
      this.service.snapshot(this.entrepriseId(), this.snapshotId()).subscribe({
        next: (s) => this.snapshot.set(s),
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de lire la version finale.'),
      });
    });
  }

  libelle(texte: string): string {
    const t = texte.replace(/_/g, ' ').trim();
    return t.charAt(0).toUpperCase() + t.slice(1);
  }

  etat(cle: string): EtatDto {
    return this.snapshot()!.payload[cle] as EtatDto;
  }

  sections(manuel: Record<string, Record<string, { value: string }>> | null): { cle: string; valeur: string }[] {
    const resultat: { cle: string; valeur: string }[] = [];
    for (const [section, champs] of Object.entries(manuel ?? {})) {
      for (const champ of Object.values(champs)) {
        resultat.push({ cle: section, valeur: champ.value });
      }
    }
    return resultat;
  }
}
