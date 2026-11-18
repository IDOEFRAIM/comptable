import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { ApercuReprise, ResultatReprise } from '../comptabilite/models/comptabilite.model';

const MODELE = 'Compte;Libellé;Tiers;Débit;Crédit\n521;Banque;;1000000;\n571;Caisse;;50000;\n411;Clients;Client Alpha;300000;\n401;Fournisseurs;Station XYZ;;200000\n101;Capital social;;;1150000\n';

/**
 * Reprise d'une balance d'ouverture : on apporte la balance de l'ancien logiciel (Excel ou CSV) au lieu de tout ressaisir. L'aperçu explique chaque ligne sans rien écrire ; l'import crée une seule
 * écriture d'à-nouveaux en brouillon, à relire puis valider dans « Écritures ».
 */
@Component({
  selector: 'app-reprise-balance',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <div class="page">
      <h2>Reprise de balance</h2>
      <p class="intro">
        Vous changez d’outil ? Importez la <strong>balance au premier jour de l’exercice</strong> (comptes de bilan, classes 1 à 5), depuis Excel (.xlsx) ou un fichier CSV.
        Colonnes : <strong>Compte</strong>, <strong>Débit</strong>, <strong>Crédit</strong>, et si possible <strong>Libellé</strong> et <strong>Tiers</strong> (obligatoire pour les comptes clients 411 et fournisseurs 401, une ligne par tiers).
        Les comptes de gestion (classes 6 et 7) ne se reprennent pas : ils se saisissent par les écritures de l’exercice.
      </p>
      <p><button type="button" class="lien" (click)="telechargerModele()" data-action="modele">Télécharger un modèle (CSV)</button></p>

      @if (resultat(); as r) {
        <div class="succes" role="status" data-zone="reprise-faite">
          <strong>Balance d’ouverture importée en brouillon.</strong>
          {{ r.lignes }} lignes@if (r.tiersCrees > 0) {, {{ r.tiersCrees }} tiers créé(s)}. Relisez l’écriture <strong>{{ r.ecriture.numeroPiece }}</strong> puis validez-la.
          <a [routerLink]="['/entreprises', entrepriseId(), 'ecritures']">Ouvrir les écritures</a>
        </div>
      } @else {
        <div class="depot">
          <input type="file" accept=".xlsx,.csv,.txt,text/csv" (change)="choisir($event)" data-zone="fichier-reprise" />
          <button type="button" class="bouton-principal" [disabled]="!fichier() || enCours()" (click)="analyser()" data-action="analyser-reprise">{{ enCours() && !apercu() ? 'Lecture…' : 'Analyser le fichier' }}</button>
        </div>
      }

      @if (erreur()) {
        <p class="erreur" role="alert" data-zone="erreur-reprise">{{ erreur() }}</p>
      }

      @if (apercu(); as a) {
        @if (!resultat()) {
          <div class="bilan" [class.ok]="a.importable" [class.ko]="!a.importable" data-zone="bilan-reprise">
            <div>Date d’ouverture : <strong>{{ a.dateOuverture }}</strong></div>
            <div>Total débit : <strong>{{ a.totalDebit | number: '1.0-2' }}</strong> · Total crédit : <strong>{{ a.totalCredit | number: '1.0-2' }}</strong></div>
            @if (a.tiersACreer > 0) {
              <div>{{ a.tiersACreer }} client(s) ou fournisseur(s) seront créés avec leur compte.</div>
            }
            @if (a.blocage) {
              <div class="blocage" data-zone="blocage-reprise">{{ a.blocage }}</div>
            } @else {
              <div>Tout est cohérent : l’écriture sera créée <strong>en brouillon</strong>, vous la validerez ensuite.</div>
            }
          </div>

          @for (av of a.avertissements; track av) {
            <p class="avertissement">{{ av }}</p>
          }

          <table class="tableau" data-zone="table-reprise">
            <thead><tr><th>Ligne</th><th>Compte</th><th>Libellé</th><th>Tiers</th><th class="n">Débit</th><th class="n">Crédit</th><th>État</th></tr></thead>
            <tbody>
              @for (l of a.lignes; track l.ligne) {
                <tr [class.erreur-ligne]="l.statut === 'ERREUR'">
                  <td>{{ l.ligne }}</td>
                  <td>{{ l.compte }}</td>
                  <td>{{ l.libelle }}</td>
                  <td>{{ l.tiers }}</td>
                  <td class="n">{{ l.debit === null ? '' : (l.debit | number: '1.0-2') }}</td>
                  <td class="n">{{ l.credit === null ? '' : (l.credit | number: '1.0-2') }}</td>
                  <td>
                    @if (l.statut === 'OK') { <span class="badge ok">OK</span> }
                    @if (l.statut === 'TIERS_A_CREER') { <span class="badge nouveau">Tiers à créer</span> }
                    @if (l.statut === 'ERREUR') { <span class="badge ko">Erreur</span> }
                    @if (l.message) { <div class="message">{{ l.message }}</div> }
                  </td>
                </tr>
              }
            </tbody>
          </table>

          <div class="actions">
            <button type="button" class="bouton-principal" [disabled]="!a.importable || enCours()" (click)="importer()" data-action="importer-reprise">{{ enCours() ? 'Import…' : 'Importer en brouillon' }}</button>
            @if (!a.importable) {
              <span class="secondaire">Corrigez le fichier puis relancez « Analyser le fichier ».</span>
            }
          </div>
        }
      }
    </div>
  `,
  styles: [
    `
      .page { max-width: 1000px; display: flex; flex-direction: column; gap: 0.8rem; }
      h2 { margin: 0; }
      .intro { margin: 0; color: var(--text-secondary, #475467); font-size: 0.875rem; line-height: 1.5; }
      .lien { background: none; border: none; padding: 0; color: var(--color-primary, #14662f); text-decoration: underline; cursor: pointer; font: inherit; font-size: 0.875rem; }
      .depot { display: flex; gap: 0.8rem; align-items: center; flex-wrap: wrap; }
      .erreur { color: #b42318; margin: 0; font-size: 0.875rem; }
      .succes { background: #ecf6ef; border: 1px solid #b7dcc2; border-radius: 8px; padding: 0.7rem 0.9rem; font-size: 0.875rem; }
      .bilan { border-radius: 8px; padding: 0.7rem 0.9rem; font-size: 0.875rem; display: flex; flex-direction: column; gap: 0.25rem; }
      .bilan.ok { background: #ecf6ef; border: 1px solid #b7dcc2; }
      .bilan.ko { background: #fff4d6; border: 1px solid #f2b705; }
      .blocage { font-weight: 600; }
      .avertissement { margin: 0; font-size: 0.8125rem; color: #8a5a00; }
      .tableau { width: 100%; border-collapse: collapse; font-size: 0.8125rem; background: #fff; }
      th { text-align: left; padding: 0.5rem; border-bottom: 1px solid var(--border-subtle, #eaecf0); color: var(--text-tertiary, #667085); }
      td { padding: 0.45rem 0.5rem; border-bottom: 1px solid var(--bg-subtle-hover, #f2f4f7); vertical-align: top; }
      .n { text-align: right; white-space: nowrap; }
      .erreur-ligne { background: #fef3f2; }
      .badge { border-radius: 999px; padding: 0.05rem 0.55rem; font-size: 0.75rem; font-weight: 600; }
      .badge.ok { background: #ecf6ef; color: #14662f; }
      .badge.nouveau { background: #eff8ff; color: #0b3b6e; }
      .badge.ko { background: #fee4e2; color: #b42318; }
      .message { margin-top: 0.2rem; font-size: 0.75rem; color: var(--text-secondary, #475467); }
      .actions { display: flex; gap: 0.8rem; align-items: center; }
      .secondaire { color: var(--text-tertiary, #667085); font-size: 0.8125rem; }
    `,
  ],
})
export class RepriseBalanceComponent {
  private readonly service = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), { initialValue: '' });
  readonly fichier = signal<File | null>(null);
  readonly apercu = signal<ApercuReprise | null>(null);
  readonly resultat = signal<ResultatReprise | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly enCours = signal(false);

  choisir(evenement: Event): void {
    const entree = evenement.target as HTMLInputElement;
    this.fichier.set(entree.files?.[0] ?? null);
    this.apercu.set(null);
    this.erreur.set(null);
  }

  analyser(): void {
    const f = this.fichier();
    if (!f) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.service.apercuReprise(this.entrepriseId(), f).subscribe({
      next: (a) => {
        this.apercu.set(a);
        this.enCours.set(false);
      },
      error: (e) => {
        this.apercu.set(null);
        this.enCours.set(false);
        this.erreur.set(e.error?.message ?? 'Le fichier n’a pas pu être lu.');
      },
    });
  }

  importer(): void {
    const f = this.fichier();
    if (!f || !this.apercu()?.importable) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.service.importerReprise(this.entrepriseId(), f).subscribe({
      next: (r) => {
        this.resultat.set(r);
        this.enCours.set(false);
      },
      error: (e) => {
        this.enCours.set(false);
        this.erreur.set(e.error?.message ?? 'L’import a échoué : rien n’a été enregistré.');
      },
    });
  }

  telechargerModele(): void {
    const url = URL.createObjectURL(new Blob(['﻿' + MODELE], { type: 'text/csv;charset=utf-8' }));
    const lien = document.createElement('a');
    lien.href = url;
    lien.download = 'modele-balance-ouverture.csv';
    lien.click();
    URL.revokeObjectURL(url);
  }
}
