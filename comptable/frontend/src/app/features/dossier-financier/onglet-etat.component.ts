import { Component, computed, input, output, signal } from '@angular/core';
import { ControleEquilibre, EtatDto, LigneEtatDto, TypeEtat } from './dossier-financier.model';
import { formaterMontant } from './montant';
import { IconComponent } from '../../shared/ui/icon.component';
import { StatusComponent } from '../../shared/ui/status.component';

type Mode = 'CR' | 'ACTIF' | 'PASSIF' | 'TFT';

interface Groupe {
  titre: string;
  codes: string[];
}

/** Regroupements d'affichage (libellés et montants viennent du backend ; ici seulement l'ordre visuel). */
const GROUPES_CR: Groupe[] = [
  { titre: 'Exploitation', codes: ['XA', 'TA', 'RA', 'RB', 'XB', 'TB', 'TC', 'TD', 'XC', 'TE', 'TF', 'TG', 'TH', 'TI', 'RC', 'RD', 'RE', 'RF', 'RG', 'RH', 'RI', 'RJ', 'RK', 'XD', 'TJ', 'RL', 'XE'] },
  { titre: 'Financier', codes: ['TK', 'TL', 'TM', 'RM', 'RN', 'XF', 'XG'] },
  { titre: 'Hors activités ordinaires (HAO)', codes: ['TN', 'TO', 'RO', 'RP', 'XH'] },
  { titre: 'Résultat net', codes: ['RQ', 'RS', 'XI'] },
];
const SOLDES_CR = new Set(['XA', 'XB', 'XC', 'XD', 'XE', 'XF', 'XG', 'XH', 'XI']);

const GROUPES_TFT: Groupe[] = [
  { titre: 'Trésorerie d’ouverture et activités opérationnelles', codes: ['ZA', 'FA', 'FB', 'FC', 'FD', 'FE', 'ZB'] },
  { titre: 'Investissements', codes: ['FF', 'FG', 'FH', 'FI', 'FJ', 'ZC'] },
  { titre: 'Capitaux propres', codes: ['FK', 'FL', 'FM', 'FN', 'ZD'] },
  { titre: 'Capitaux étrangers', codes: ['FO', 'FP', 'FQ', 'ZE'] },
  { titre: 'Variation de trésorerie', codes: ['ZF', 'ZG', 'ZH'] },
];

interface Section {
  titre: string | null;
  lignes: LigneEtatDto[];
}

/** Tableau d'un état (Compte de résultat, Actif, Passif, TFT) : montants backend, lignes incomplètes explicitement « Incomplet », « Pourquoi ? » à la demande. */
@Component({
  selector: 'app-onglet-etat',
  standalone: true,
  imports: [StatusComponent, IconComponent],
  template: `
    <section class="etat" [attr.data-etat]="mode()">
      <header class="etat-entete">
        <div>
          <h3 class="ui-section-title">{{ titre() }}</h3>
          <p class="ui-meta periode">
            Exercice N : {{ etat().exerciceDebut }} → {{ etat().exerciceFin }}
            @if (etat().exercicePrecedentId) { · N-1 : {{ etat().precedentDebut }} → {{ etat().precedentFin }} } @else { · pas d’exercice précédent }
          </p>
        </div>
        @if (etat().status !== 'READY') {
          <app-status [statut]="etat().status" />
        }
      </header>
      @if (etat().status !== 'READY' && etat().status !== 'FINAL' && etat().statusReasons.length) {
        <details class="raisons" data-zone="raisons-etat">
          <summary>{{ etat().statusReasons.length }} cause{{ etat().statusReasons.length > 1 ? 's' : '' }} d’incomplétude</summary>
          <ul>
            @for (r of etat().statusReasons; track r) {
              <li>{{ r }}</li>
            }
          </ul>
        </details>
      }
      <div class="ui-surface table-wrap">
        <table class="ui-table table-etat">
          <thead>
            <tr>
              <th class="ui-code">Code</th>
              <th>Libellé</th>
              @if (mode() === 'ACTIF') {
                <th class="ui-num">Brut N</th>
                <th class="ui-num">Amort. / dépr.</th>
                <th class="ui-num">Net N</th>
                <th class="ui-num">Net N-1</th>
              } @else {
                <th class="ui-num">N</th>
                <th class="ui-num">N-1</th>
                @if (mode() === 'CR') {
                  <th class="ui-num">Variation</th>
                }
              }
              @if (interactif()) {
                <th class="col-menu"><span class="ui-sr-only">Actions</span></th>
              }
            </tr>
          </thead>
          <tbody>
            @for (s of sections(); track s.titre) {
              @if (s.titre) {
                <tr class="groupe"><td [attr.colspan]="colonnes()">{{ s.titre }}</td></tr>
              }
              @for (l of s.lignes; track l.code) {
                <tr [class.solde]="estSolde(l)" [class.total]="l.calculationType === 'FORMULA'" [attr.data-ligne]="l.code">
                  <td class="ui-code">{{ l.code }}</td>
                  <td>
                    {{ l.label }}
                    @if (!l.complete && l.incompleteReasons.length) {
                      <div class="cause">
                        @for (r of l.incompleteReasons; track r) {
                          <span>{{ r }}</span>
                        }
                      </div>
                    } @else if (l.amountN === null && l.notCalculableReason) {
                      <div class="cause"><span>{{ l.notCalculableReason }}</span></div>
                    }
                  </td>
                  @if (mode() === 'ACTIF') {
                    <td class="ui-num">{{ montant(l.grossN) }}</td>
                    <td class="ui-num">{{ montant(l.depreciationN) }}</td>
                  }
                  <td class="ui-num montant" [attr.data-champ]="'montant-' + l.code">
                    @if (!l.complete) {
                      <span class="nd">Incomplet</span>
                    } @else if (l.amountN === null) {
                      <span class="nd">Non déterminé</span>
                    } @else {
                      {{ montant(l.amountN) }}
                    }
                  </td>
                  <td class="ui-num ui-muted">{{ l.amountNMinus1 === null ? (etat().exercicePrecedentId ? '' : '–') : montant(l.amountNMinus1) }}</td>
                  @if (mode() === 'CR') {
                    <td class="ui-num ui-muted">{{ montant(l.variation) }}</td>
                  }
                  @if (interactif()) {
                    <td class="col-menu">
                      <button type="button" class="ui-btn ui-btn--tertiary ui-btn--sm" (click)="basculerMenu(l.code)" [attr.aria-expanded]="menuOuvert() === l.code" aria-haspopup="menu" [attr.aria-label]="'Actions de la ligne ' + l.code" [attr.data-action]="'menu-' + l.code"><app-icon nom="more" /></button>
                      @if (menuOuvert() === l.code) {
                        <div class="menu" role="menu">
                          <button type="button" role="menuitem" (click)="ouvrir(l.code)" [attr.data-action]="'pourquoi-' + l.code">Voir le détail</button>
                          <button type="button" role="menuitem" (click)="ouvrir(l.code)">Voir les écritures</button>
                          <button type="button" role="menuitem" (click)="ouvrir(l.code)">Voir l’origine</button>
                        </div>
                      }
                    </td>
                  }
                </tr>
              }
            }
          </tbody>
        </table>
      </div>

      @if (mode() === 'ACTIF' && controle(); as c) {
        <p class="controle-equilibre" data-zone="controle-bz-dz">
          <span>Total actif (BZ) <strong>{{ montant(c.totalActif) }}</strong></span>
          <span>Total passif (DZ) <strong>{{ montant(c.totalPassif) }}</strong></span>
          <span>Écart <strong>{{ montant(c.ecart) }}</strong></span>
          @if (c.equilibre) {
            <span class="verdict" data-champ="verdict-equilibre"><app-status statut="OK" texte="Équilibré" /></span>
          } @else {
            <span class="verdict" data-champ="verdict-equilibre"><app-status statut="BLOCKED" [texte]="'Écart de ' + montant(c.ecart)" /></span>
          }
        </p>
      }

      @if (etat().unmapped.length || etat().ambiguous.length) {
        <div class="comptes-bloquants ui-notice ui-notice--danger" data-zone="comptes-bloquants">
          <div>
            <h4>Comptes empêchant la finalisation</h4>
            <ul>
              @for (a of etat().unmapped; track a.accountId) {
                <li><strong>{{ a.number }}</strong> {{ a.label }} — solde : {{ montant(a.balanceN) }} <span class="code-controle">UNMAPPED_ACCOUNT</span></li>
              }
              @for (a of etat().ambiguous; track a.accountId) {
                <li><strong>{{ a.number }}</strong> {{ a.label }} — lignes candidates : {{ a.candidateLines.join(', ') }} <span class="code-controle">AMBIGUOUS_MAPPING</span></li>
              }
            </ul>
          </div>
        </div>
      }
    </section>
  `,
  styles: [
    `
      :host { display: block; margin-bottom: var(--ui-s6); }
      .etat-entete { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--ui-s4); margin-bottom: var(--ui-s3); }
      .periode { margin: var(--ui-s1) 0 0; }
      .raisons { margin: 0 0 var(--ui-s3); font-size: var(--ui-fs-sm); color: var(--ui-text-2); }
      .raisons summary { cursor: pointer; }
      .raisons ul { margin: var(--ui-s2) 0 0; padding-left: var(--ui-s5); }
      .table-etat th.ui-num, .table-etat td.ui-num { width: 128px; }
      .col-menu button { color: var(--ui-text-2); }
      .table-etat tr.groupe td { padding-top: var(--ui-s4); border-bottom: 1px solid var(--ui-line-strong); font-size: var(--ui-fs-xs); font-weight: var(--ui-fw-semibold); letter-spacing: 0.04em; text-transform: uppercase; color: var(--ui-text-3); background: var(--ui-surface-subtle); }
      .table-etat tr.total td { font-weight: var(--ui-fw-semibold); border-top: 1px solid var(--ui-line-strong); }
      .table-etat tr.solde td { font-weight: var(--ui-fw-semibold); border-top: 1px solid var(--ui-line-strong); background: var(--ui-surface-subtle); }
      .montant { font-weight: var(--ui-fw-medium); }
      .nd { color: var(--ui-text-2); font-style: italic; font-weight: var(--ui-fw-medium); }
      .cause { margin-top: 2px; font-size: var(--ui-fs-xs); color: var(--ui-text-2); }
      .cause span { display: block; }
      .col-menu { position: relative; width: 40px; text-align: right; }
      .menu { position: absolute; right: var(--ui-s3); top: 100%; z-index: 5; min-width: 180px; padding: var(--ui-s1); background: var(--ui-surface); border: 1px solid var(--ui-line-strong); border-radius: var(--ui-r); box-shadow: 0 4px 12px rgba(25, 33, 46, 0.08); text-align: left; }
      .menu button { display: block; width: 100%; padding: var(--ui-s2) var(--ui-s3); border: 0; border-radius: var(--ui-r); background: transparent; color: var(--ui-text); font: inherit; font-size: var(--ui-fs-sm); text-align: left; cursor: pointer; }
      .menu button:hover { background: var(--ui-bg); }
      .controle-equilibre { display: flex; flex-wrap: wrap; align-items: center; gap: var(--ui-s6); margin: var(--ui-s3) 0 0; padding: var(--ui-s3) var(--ui-s4); border-top: 1px solid var(--ui-line-strong); font-size: var(--ui-fs-sm); color: var(--ui-text-2); }
      .controle-equilibre strong { margin-left: var(--ui-s2); font-variant-numeric: tabular-nums; color: var(--ui-text); }
      .comptes-bloquants { margin-top: var(--ui-s4); }
      .comptes-bloquants h4 { margin: 0 0 var(--ui-s1); font-size: var(--ui-fs-sm); }
      .comptes-bloquants ul { margin: 0; padding-left: var(--ui-s5); }
      .code-controle { font-family: var(--ui-mono); font-size: var(--ui-fs-xs); color: var(--ui-text-3); }
    `,
  ],
})
export class OngletEtatComponent {
  readonly etat = input.required<EtatDto>();
  readonly mode = input.required<Mode>();
  readonly titre = input.required<string>();
  readonly controle = input<ControleEquilibre | null>(null);
  readonly interactif = input(true);
  readonly pourquoi = output<{ type: TypeEtat; code: string }>();

  readonly typeEtat = computed<TypeEtat>(() => ({ CR: 'COMPTE_RESULTAT', ACTIF: 'BILAN_ACTIF', PASSIF: 'BILAN_PASSIF', TFT: 'TFT' })[this.mode()] as TypeEtat);
  readonly colonnes = computed(() => (this.mode() === 'ACTIF' ? 6 : this.mode() === 'CR' ? 5 : 4) + (this.interactif() ? 1 : 0));
  readonly menuOuvert = signal<string | null>(null);

  basculerMenu(code: string): void {
    this.menuOuvert.update((c) => (c === code ? null : code));
  }

  ouvrir(code: string): void {
    this.menuOuvert.set(null);
    this.pourquoi.emit({ type: this.typeEtat(), code });
  }

  readonly sections = computed<Section[]>(() => {
    const lignes = [...this.etat().lines].sort((a, b) => a.order - b.order);
    const groupes = this.mode() === 'CR' ? GROUPES_CR : this.mode() === 'TFT' ? GROUPES_TFT : null;
    if (!groupes) {
      return [{ titre: null, lignes }];
    }
    const parCode = new Map(lignes.map((l) => [l.code, l]));
    const vus = new Set<string>();
    const sections: Section[] = [];
    for (const g of groupes) {
      const ls = g.codes.map((c) => parCode.get(c)).filter((l): l is LigneEtatDto => !!l);
      ls.forEach((l) => vus.add(l.code));
      if (ls.length) {
        sections.push({ titre: g.titre, lignes: ls.sort((a, b) => a.order - b.order) });
      }
    }
    const autres = lignes.filter((l) => !vus.has(l.code));
    if (autres.length) {
      sections.push({ titre: 'Autres lignes', lignes: autres });
    }
    return sections;
  });

  estSolde(l: LigneEtatDto): boolean {
    return this.mode() === 'CR' ? SOLDES_CR.has(l.code) : this.mode() === 'TFT' ? ['ZA', 'ZB', 'ZC', 'ZD', 'ZE', 'ZF', 'ZG', 'ZH'].includes(l.code) : false;
  }

  montant = formaterMontant;
}
