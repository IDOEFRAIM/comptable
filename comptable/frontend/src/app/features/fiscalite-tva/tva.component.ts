import { NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs/operators';
import { formaterMontant } from '../dossier-financier/montant';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { StatusComponent } from '../../shared/ui/status.component';
import { Ajustement, Controles, Declaration, FiltresOperations, LigneTva, Origine, Page, Periode, PeriodeResume, StatutOperationnel, Traitement } from './tva.model';
import { TvaService } from './tva.service';

type Onglet = 'VUE' | 'VENTES' | 'ACHATS' | 'AJUSTEMENTS' | 'CONTROLES' | 'DECLARATION';

const ONGLETS: { code: Onglet; libelle: string }[] = [
  { code: 'VUE', libelle: 'Vue d’ensemble' },
  { code: 'VENTES', libelle: 'Ventes' },
  { code: 'ACHATS', libelle: 'Achats' },
  { code: 'AJUSTEMENTS', libelle: 'Ajustements' },
  { code: 'CONTROLES', libelle: 'Contrôles' },
  { code: 'DECLARATION', libelle: 'Déclaration' },
];

const TRAITEMENTS: Record<Traitement, string> = {
  OUTPUT_TAX: 'TVA collectée',
  INPUT_TAX: 'TVA déductible',
  EXEMPT: 'Exonérée',
  OUT_OF_SCOPE: 'Hors champ',
  NON_DEDUCTIBLE: 'Non déductible',
  PARTIALLY_DEDUCTIBLE: 'Partiellement déductible',
  TO_REVIEW: 'À revoir',
};

const OPTIONS_PAR_COTE: Record<string, Traitement[]> = {
  OUTPUT: ['OUTPUT_TAX', 'EXEMPT', 'OUT_OF_SCOPE'],
  INPUT: ['INPUT_TAX', 'NON_DEDUCTIBLE', 'PARTIALLY_DEDUCTIBLE', 'EXEMPT', 'OUT_OF_SCOPE'],
  UNDETERMINED: ['OUTPUT_TAX', 'INPUT_TAX', 'EXEMPT', 'OUT_OF_SCOPE', 'NON_DEDUCTIBLE', 'PARTIALLY_DEDUCTIBLE'],
};

const ANOMALIES: Record<string, string> = {
  TAX_TREATMENT_MISSING: 'Traitement fiscal à décider',
  TAX_RATE_UNRESOLVED: 'Taux non reconnu par le cadre fiscal',
  VAT_BASE_MISMATCH: 'Base différente de la facture',
  VAT_AMOUNT_MISMATCH: 'TVA différente du calcul',
  DUPLICATE_TAX_DOCUMENT: 'Document déjà pris en compte',
  TAX_PERIOD_INCOMPLETE: 'Période incomplète',
  TAX_FRAMEWORK_NOT_VERIFIED: 'Cadre réglementaire non vérifié',
};

const STATUTS_OPERATIONNELS: Record<StatutOperationnel, string> = {
  A_PREPARER: 'À préparer',
  EN_PREPARATION: 'En préparation',
  ATTENTION_REQUISE: 'Attention requise',
  PRET_POUR_REVUE: 'Prête pour revue',
  EN_REVUE: 'Revue faite, finalisation bloquée',
  PRET_A_FINALISER: 'Prête à finaliser',
  FINALISE: 'Finalisée',
  A_PAYER: 'À payer',
  PARTIELLEMENT_PAYE: 'Partiellement payée',
  PAYE: 'Payée',
  CREDIT_A_REPORTER: 'Crédit à reporter',
};

const RAISONS_BLOCAGE: Record<string, string> = {
  REGULATORY_PACK_NOT_VERIFIED: 'Aucun cadre réglementaire vérifié ne couvre cette période.',
  FISCAL_AMOUNTS_INCOMPLETE: 'Des montants fiscaux ne peuvent pas être déterminés avec le cadre applicable.',
  OPERATIONS_TO_REVIEW: 'Des opérations restent à décider.',
  CONTROLS_OPEN: 'Des contrôles sont ouverts.',
  UNEXPLAINED_GAP: 'Un écart entre la TVA comptable et la TVA calculée n’est pas expliqué.',
  OPENING_CREDIT_TO_REVIEW: 'Le crédit d’ouverture saisi n’est pas validé par un cadre vérifié.',
};

const GENRES = [
  { code: 'CORRECTION', libelle: 'Correction' },
  { code: 'REGULARISATION', libelle: 'Régularisation' },
  { code: 'PRORATA', libelle: 'Prorata' },
  { code: 'NON_DEDUCTIBILITY', libelle: 'Non-déductibilité' },
  { code: 'ROUNDING', libelle: 'Arrondi' },
  { code: 'MANUAL', libelle: 'Ajustement manuel' },
];

/**
 * Espace TVA d'une entreprise : période, ventes, achats, ajustements, contrôles, déclaration. Tous les montants et statuts viennent du backend (calcul BigDecimal, cadre fiscal vérifié, constats) ; l'écran
 * ne calcule aucune TVA. Sans cadre fiscal vérifié, les montants affichés sont comptabilisés, jamais déclarables.
 */
@Component({
  selector: 'app-tva',
  standalone: true,
  imports: [NgTemplateOutlet, FormsModule, StatusComponent, IconComponent, EmptyStateComponent, SectionHeaderComponent],
  template: `
    <div class="page-tva" data-zone="tva">
      <header class="entete-page">
        <div>
          <h1 class="ui-page-title">TVA</h1>
          <p class="ui-meta">Calculée depuis la comptabilité ; la comptabilité n’est jamais modifiée.</p>
        </div>
        <div class="selecteur">
          @if (periodes().length) {
            <label class="ui-sr-only" for="tva-periode">Période</label>
            <select id="tva-periode" class="ui-input" [ngModel]="periodeId()" (ngModelChange)="choisirPeriode($event)" data-champ="periode">
              @for (p of periodes(); track p.id) { <option [value]="p.id">{{ p.periodStart }} → {{ p.periodEnd }}</option> }
            </select>
          }
          <button type="button" class="ui-btn ui-btn--secondary" (click)="formulairePeriode.set(!formulairePeriode())" data-action="nouvelle-periode">Ouvrir une période</button>
        </div>
      </header>

      @if (formulairePeriode()) {
        <form class="ui-surface formulaire" (ngSubmit)="ouvrirPeriode()" data-zone="form-periode">
          <div class="ui-field"><label class="ui-field__label" for="tva-debut">Début</label><input id="tva-debut" name="debut" type="date" class="ui-input" [(ngModel)]="nouvelle.debut" data-champ="debut" /></div>
          <div class="ui-field"><label class="ui-field__label" for="tva-fin">Fin</label><input id="tva-fin" name="fin" type="date" class="ui-input" [(ngModel)]="nouvelle.fin" data-champ="fin" /></div>
          <button type="submit" class="ui-btn ui-btn--primary" [disabled]="!nouvelle.debut || !nouvelle.fin" data-action="ouvrir-periode">Ouvrir</button>
        </form>
      }
      @if (erreur()) {
        <p class="ui-notice ui-notice--danger" role="alert" data-zone="erreur-tva">{{ erreur() }}
          @if (conflit()) { <button type="button" class="ui-btn ui-btn--link" (click)="recharger()" data-action="recharger">Recharger les dernières données</button> }
        </p>
      }

      @if (periode(); as p) {
        <section class="statut-ligne" data-zone="statut-periode">
          <app-status [statut]="p.status" [texte]="libelleStatut(p.status)" />
          <span class="ui-meta">{{ p.periodStart }} → {{ p.periodEnd }}</span>
          <span class="ui-meta" data-champ="statut-operationnel">{{ libelleOperationnel(p.progress.status) }} · {{ p.progress.readySteps }} / {{ p.progress.totalSteps }} étapes prêtes</span>
          <span class="ui-meta" data-champ="cadre-reglementaire">Cadre réglementaire : {{ p.regulatoryPackVerified ? 'vérifié (' + (p.framework?.version ?? '') + ')' : 'non vérifié' }}</span>
          <div class="actions">
            <button type="button" class="ui-btn ui-btn--tertiary" (click)="agir('recalculer')" [disabled]="enCours() || p.status === 'FINAL' || !p.permissions.prepare" data-action="recalculer"><app-icon nom="refresh" />Recalculer</button>
            @if (p.status === 'READY_FOR_REVIEW' || p.status === 'REVIEWED') {
              <button type="button" class="ui-btn ui-btn--tertiary" (click)="agir('rouvrir')" [disabled]="enCours() || !p.permissions.prepare" data-action="rouvrir">Rouvrir</button>
            }
            @if ((p.status === 'OPEN' || p.status === 'PREPARING') && p.permissions.prepare) {
              <button type="button" class="ui-btn ui-btn--primary" (click)="agir('preparer')" [disabled]="enCours()" data-action="preparer">Marquer prête pour revue</button>
            }
            @if (p.status === 'READY_FOR_REVIEW' && p.permissions.review) {
              <button type="button" class="ui-btn ui-btn--primary" (click)="agir('revoir')" [disabled]="enCours()" data-action="revoir">Marquer comme revue</button>
            }
            @if (p.status === 'REVIEWED' && p.permissions.finalise) {
              <button type="button" class="ui-btn ui-btn--primary" (click)="confirmationFinal.set(true)" [disabled]="enCours() || !p.finalisable" data-action="finaliser">Finaliser la déclaration</button>
            }
          </div>
        </section>
        @if (p.basis === 'ACCOUNTED_UNVERIFIED' && p.status !== 'FINAL') {
          <p class="ui-notice" data-zone="bandeau-non-verifie">
            <app-icon nom="alert-triangle" />
            <span>{{ p.framework ? 'Le cadre fiscal applicable n’est pas vérifié ou incomplet' : 'Aucun cadre fiscal vérifié pour cette période' }} : les montants sont <strong>comptabilisés, non déclarables</strong>. La déclaration ne peut pas être finalisée.
              @if (!p.regulatoryPackVerified) { <span data-champ="explication-cadre"> Cette période peut être préparée et contrôlée, mais elle ne peut pas être finalisée tant qu’un cadre réglementaire vérifié n’est pas installé.</span> }</span>
          </p>
        }
        @if (confirmationFinal()) {
          <div class="ui-notice ui-notice--info" data-zone="confirmation-finalisation">
            <div>
              <p><strong>La finalisation crée un snapshot fiscal immuable de cette déclaration.</strong></p>
              <p>Les modifications ultérieures de la comptabilité ne la changeront pas.</p>
              <div class="actions-confirmation">
                <button type="button" class="ui-btn ui-btn--primary" [disabled]="enCours()" (click)="agir('finaliser'); confirmationFinal.set(false)" data-action="confirmer-finalisation">Confirmer la finalisation</button>
                <button type="button" class="ui-btn ui-btn--tertiary" (click)="confirmationFinal.set(false)">Annuler</button>
              </div>
            </div>
          </div>
        }

        <nav class="ui-tabs" role="tablist" aria-label="Sections TVA">
          @for (o of onglets; track o.code) {
            <button type="button" role="tab" class="ui-tab" [attr.aria-selected]="onglet() === o.code" (click)="ouvrirOnglet(o.code)" [attr.data-onglet]="o.code">
              {{ o.libelle }}
              @if (o.code === 'CONTROLES' && p.anomalies > 0) { <span class="ui-tab__count">{{ p.anomalies }}</span> }
              @if (o.code === 'VENTES' && ventesARevoir() > 0) { <span class="ui-tab__count">{{ ventesARevoir() }}</span> }
            </button>
          }
        </nav>

        <div class="contenu">
          @switch (onglet()) {
            @case ('VUE') {
              <dl class="resume" data-zone="vue-ensemble">
                <div class="resume__item"><dt>TVA collectée</dt><dd data-champ="tva-collectee">{{ montant(p.totals.declaredOutput) }}</dd></div>
                <div class="resume__item"><dt>TVA déductible</dt><dd data-champ="tva-deductible">{{ montant(p.totals.declaredInput) }}</dd></div>
                <div class="resume__item"><dt>Ajustements collectée</dt><dd data-champ="ajustements">{{ montant(p.totals.adjustmentsOutput) }}</dd></div>
                <div class="resume__item"><dt>Ajustements déductible</dt><dd data-champ="ajustements-deductible">{{ montant(p.totals.adjustmentsInput) }}</dd></div>
                <div class="resume__item resume__item--net"><dt>Solde net</dt><dd data-champ="solde">{{ montant(p.totals.netDeclared) }}</dd></div>
                <div class="resume__item"><dt>À revoir</dt><dd data-champ="a-revoir">{{ p.toReview }}</dd></div>
              </dl>
              <p class="ui-meta base">{{ p.basis === 'FISCAL_VERIFIED' ? 'Montants calculés avec le cadre fiscal vérifié ' + (p.framework?.version ?? '') + '.' : 'Montants comptabilisés (cadre fiscal non vérifié).' }}
                {{ p.totals.operations }} opération{{ p.totals.operations > 1 ? 's' : '' }}.</p>
              <p class="ui-meta" data-champ="echeance">{{ p.deadline ? 'Date limite : ' + p.deadline + ' (règle du cadre vérifié)' : 'Date limite : non fournie (aucune règle d’échéance vérifiée)' }}</p>
              <section class="ui-section" data-zone="progression">
                <app-section-header titre="Progression" [sousTitre]="p.progress.readySteps + ' / ' + p.progress.totalSteps + ' étapes prêtes'" />
                <ul class="etapes">
                  @for (e of p.progress.steps; track e.code) {
                    <li [attr.data-etape]="e.code" [attr.data-prete]="e.ready">
                      <app-status [statut]="e.ready ? 'OK' : e.blocking ? 'BLOCKED' : 'DRAFT'" [texte]="e.label" />
                    </li>
                  }
                </ul>
              </section>
              @if (p.status === 'FINAL') {
                <section class="ui-section" data-zone="reglement">
                  <app-section-header titre="Règlement" sousTitre="Suivi interne : aucun télépaiement." />
                  <dl class="resume">
                    <div class="resume__item"><dt>Solde déclaré</dt><dd data-champ="solde-declare">{{ montant(p.payment.declaredAmount) }}</dd></div>
                    <div class="resume__item"><dt>Payé</dt><dd data-champ="paye">{{ montant(p.payment.paidAmount) }}</dd></div>
                    <div class="resume__item"><dt>Reste à payer</dt><dd data-champ="reste-a-payer">{{ montant(p.payment.remainingAmount) }}</dd></div>
                  </dl>
                  @if (p.payment.payments.length) {
                    <table class="ui-table historique" data-zone="historique-paiements">
                      <thead><tr><th>Date</th><th>Référence</th><th>Source</th><th class="ui-num">Montant</th><th>Statut</th><th></th></tr></thead>
                      <tbody>
                        @for (x of p.payment.payments; track x.id) {
                          <tr [attr.data-paiement]="x.id" [class.annule]="x.status === 'REVERSED'">
                            <td>{{ x.paymentDate }}</td><td>{{ x.reference || '—' }}</td><td>{{ x.source }}</td><td class="ui-num">{{ montant(x.amount) }}</td>
                            <td>{{ x.status === 'ACTIVE' ? 'Enregistré' : 'Annulé : ' + x.reverseReason }}</td>
                            <td class="col-action">@if (x.status === 'ACTIVE' && p.permissions.prepare) { <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="paiementAAnnuler.set(x.id)" data-action="annuler-paiement">Annuler</button> }</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                  @if (paiementAAnnuler()) {
                    <div class="ui-surface formulaire" data-zone="form-annulation-paiement">
                      <div class="ui-field plein"><label class="ui-field__label" for="pa-motif">Motif de l’annulation<span class="ui-required" aria-hidden="true">*</span></label>
                        <input id="pa-motif" class="ui-input" [ngModel]="motifPaiement()" (ngModelChange)="motifPaiement.set($event)" data-champ="motif-annulation-paiement" /></div>
                      <div class="actions-formulaire">
                        <button type="button" class="ui-btn ui-btn--primary" [disabled]="!motifPaiement().trim() || enCours()" (click)="annulerPaiement()" data-action="confirmer-annulation-paiement">Confirmer l’annulation</button>
                        <button type="button" class="ui-btn ui-btn--tertiary" (click)="paiementAAnnuler.set(null)">Fermer</button>
                      </div>
                    </div>
                  }
                  @if (p.payment.paymentOpen && p.permissions.prepare) {
                    <form class="ui-surface formulaire" (ngSubmit)="enregistrerPaiement()" data-zone="form-paiement">
                      <div class="ui-field"><label class="ui-field__label" for="pay-montant">Montant payé</label>
                        <input id="pay-montant" name="montant" type="number" step="0.01" class="ui-input" [(ngModel)]="paiement.amount" data-champ="paiement-montant" /></div>
                      <div class="ui-field"><label class="ui-field__label" for="pay-date">Date</label>
                        <input id="pay-date" name="date" type="date" class="ui-input" [(ngModel)]="paiement.date" data-champ="paiement-date" /></div>
                      <div class="ui-field"><label class="ui-field__label" for="pay-ref">Référence</label>
                        <input id="pay-ref" name="ref" class="ui-input" [(ngModel)]="paiement.reference" data-champ="paiement-reference" /></div>
                      <div class="ui-field"><label class="ui-field__label" for="pay-source">Source</label>
                        <select id="pay-source" name="source" class="ui-input" [(ngModel)]="paiement.source" data-champ="paiement-source">
                          <option value="MANUAL">Saisie manuelle</option><option value="BANK_STATEMENT">Relevé bancaire</option><option value="RECEIPT">Quittance</option><option value="OTHER">Autre</option></select></div>
                      <div class="actions-formulaire"><button type="submit" class="ui-btn ui-btn--primary" [disabled]="!paiementValide() || enCours()" data-action="enregistrer-paiement">Enregistrer le paiement</button></div>
                    </form>
                  }
                </section>
              }
              <section class="ui-section" data-zone="credit">
                <app-section-header titre="Crédit de TVA" />
                <dl class="resume">
                  <div class="resume__item"><dt>Crédit d’ouverture</dt><dd data-champ="credit-ouverture">{{ p.credit.openingAmount === null ? '—' : montant(p.credit.openingAmount) }}</dd></div>
                  <div class="resume__item"><dt>Crédit de clôture</dt><dd data-champ="credit-cloture">{{ p.credit.closingAmount === null ? '—' : montant(p.credit.closingAmount) }}</dd></div>
                  <div class="resume__item"><dt>Validation</dt><dd data-champ="credit-statut">{{ libelleCredit(p.credit.openingState, p.credit.closingState) }}</dd></div>
                </dl>
                @if (p.permissions.prepare && p.status !== 'FINAL') {
                  <button type="button" class="ui-btn ui-btn--tertiary" (click)="formulaireCredit.set(!formulaireCredit())" data-action="saisir-credit">Saisir un crédit d’ouverture</button>
                  @if (formulaireCredit()) {
                    <form class="ui-surface formulaire" (ngSubmit)="enregistrerCredit()" data-zone="form-credit">
                      <div class="ui-field"><label class="ui-field__label" for="cr-montant">Montant</label><input id="cr-montant" name="montant" type="number" step="0.01" class="ui-input" [(ngModel)]="credit.amount" data-champ="credit-montant" /></div>
                      <div class="ui-field"><label class="ui-field__label" for="cr-source">Source<span class="ui-required" aria-hidden="true">*</span></label><input id="cr-source" name="source" class="ui-input" [(ngModel)]="credit.source" data-champ="credit-source" /></div>
                      <div class="ui-field plein"><label class="ui-field__label" for="cr-motif">Justification<span class="ui-required" aria-hidden="true">*</span></label><textarea id="cr-motif" name="motif" rows="2" class="ui-input" [(ngModel)]="credit.reason" data-champ="credit-motif"></textarea></div>
                      <div class="actions-formulaire"><button type="submit" class="ui-btn ui-btn--primary" [disabled]="!creditValide() || enCours()" data-action="enregistrer-credit">Enregistrer</button></div>
                    </form>
                  }
                }
              </section>
              <section class="ui-section">
                <app-section-header titre="Rapprochement comptabilité ↔ fiscalité" />
                <table class="ui-table rapprochement" data-zone="rapprochement">
                  <thead><tr><th></th><th class="ui-num">Grand livre</th><th class="ui-num">Opérations de la période</th></tr></thead>
                  <tbody>
                    <tr><td>TVA collectée comptabilisée</td><td class="ui-num">{{ montant(p.reconciliation.ledgerOutput) }}</td><td class="ui-num">{{ montant(p.reconciliation.operationsOutput) }}</td></tr>
                    <tr><td>TVA récupérable comptabilisée</td><td class="ui-num">{{ montant(p.reconciliation.ledgerInput) }}</td><td class="ui-num">{{ montant(p.reconciliation.operationsInput) }}</td></tr>
                  </tbody>
                </table>
                <p class="ui-meta">Écart non expliqué (ni décision, ni ajustement) : <strong data-champ="ecart-inexplique">{{ montant(p.totals.unexplained) }}</strong></p>
              </section>
              @if (p.finalisationBlockers.length && p.status !== 'FINAL') {
                <section class="ui-section" data-zone="bloqueurs">
                  <app-section-header titre="Avant la finalisation" [compteur]="p.finalisationBlockers.length" />
                  <ul class="liste-simple">@for (b of p.finalisationBlockers; track b) { <li>{{ b }}</li> }</ul>
                  @if (p.finalisationReasons.length) {
                    <ul class="liste-simple" data-zone="raisons-blocage">@for (r of p.finalisationReasons; track r) { <li [attr.data-raison]="r">{{ libelleRaison(r) }}</li> }</ul>
                  }
                </section>
              }
            }
            @case ('VENTES') { <ng-container *ngTemplateOutlet="tableau; context: { cote: 'OUTPUT' }" /> }
            @case ('ACHATS') { <ng-container *ngTemplateOutlet="tableau; context: { cote: 'INPUT' }" /> }
            @case ('AJUSTEMENTS') {
              <section data-zone="ajustements">
                <app-section-header titre="Ajustements" [compteur]="ajustementsActifs()">
                  @if (p.permissions.prepare && p.status !== 'FINAL') {
                    <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="formulaireAjustement.set(!formulaireAjustement())" data-action="nouvel-ajustement">Ajouter un ajustement</button>
                  }
                </app-section-header>
                @if (formulaireAjustement()) {
                  <form class="ui-surface formulaire" (ngSubmit)="ajouterAjustement()" data-zone="form-ajustement">
                    <div class="ui-field"><label class="ui-field__label" for="aj-genre">Genre</label>
                      <select id="aj-genre" name="genre" class="ui-input" [(ngModel)]="ajustement.kind" data-champ="ajustement-genre">@for (g of genres; track g.code) { <option [value]="g.code">{{ g.libelle }}</option> }</select></div>
                    <div class="ui-field"><label class="ui-field__label" for="aj-effet">Effet</label>
                      <select id="aj-effet" name="effet" class="ui-input" [(ngModel)]="ajustement.effect" data-champ="ajustement-effet"><option value="OUTPUT">TVA collectée</option><option value="INPUT">TVA déductible</option></select></div>
                    <div class="ui-field"><label class="ui-field__label" for="aj-montant">Montant</label>
                      <input id="aj-montant" name="montant" type="number" step="0.01" class="ui-input" [(ngModel)]="ajustement.amount" data-champ="ajustement-montant" />
                      <span class="ui-field__hint">Positif : augmente la TVA de ce côté ; négatif : la diminue.</span></div>
                    <div class="ui-field plein"><label class="ui-field__label" for="aj-motif">Justification<span class="ui-required" aria-hidden="true">*</span></label>
                      <textarea id="aj-motif" name="motif" rows="2" class="ui-input" [(ngModel)]="ajustement.reason" data-champ="ajustement-motif"></textarea></div>
                    <div class="ui-field"><label class="ui-field__label" for="aj-operation">Opération concernée</label>
                      <select id="aj-operation" name="operation" class="ui-input" [(ngModel)]="ajustement.ecritureId" data-champ="ajustement-operation">
                        <option [ngValue]="null">Aucune (ajustement de période)</option>
                        @for (o of operationsPourAjustement(); track o.ecritureId) { <option [ngValue]="o.ecritureId">{{ o.piece || o.libelle }} — {{ montant(o.vatAccounted) }}</option> }
                      </select></div>
                    <div class="actions-formulaire">
                      <button type="submit" class="ui-btn ui-btn--primary" [disabled]="!ajustementValide() || enCours()" data-action="enregistrer-ajustement">Enregistrer</button>
                      <button type="button" class="ui-btn ui-btn--tertiary" (click)="formulaireAjustement.set(false)">Annuler</button>
                    </div>
                  </form>
                }
                @if (ajustements().length) {
                  <div class="ui-surface">
                    <table class="ui-table">
                      <thead><tr><th>Genre</th><th>Effet</th><th class="ui-num">Montant</th><th>Justification</th><th></th></tr></thead>
                      <tbody>
                        @for (a of ajustements(); track a.id) {
                          <tr [class.annule]="a.cancelled" [attr.data-ajustement]="a.id">
                            <td>{{ libelleGenre(a.kind) }}</td>
                            <td>{{ a.effect === 'OUTPUT' ? 'TVA collectée' : 'TVA déductible' }}</td>
                            <td class="ui-num">{{ montant(a.amount) }}</td>
                            <td>{{ a.reason }}@if (a.cancelled) { <span class="ui-muted"> — annulé : {{ a.cancelReason }}</span> }</td>
                            <td class="col-action">@if (!a.cancelled && p.permissions.prepare && p.status !== 'FINAL') { <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="annulerAjustement(a)" data-action="annuler-ajustement">Annuler</button> }</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                } @else {
                  <app-empty-state icone="file" titre="Aucun ajustement" detail="Un ajustement corrige ou régularise la TVA calculée ; il est toujours justifié et tracé." />
                }
              </section>
            }
            @case ('CONTROLES') {
              <section data-zone="controles">
                @if (controles(); as c) {
                  @if (c.anomalies.length) {
                    <ul class="inbox">
                      @for (a of c.anomalies; track $index) {
                        <li class="item" [attr.data-anomalie]="a.code">
                          <span class="item__icone"><app-icon nom="alert-circle" /></span>
                          <div class="item__corps">
                            <p class="item__titre">{{ libelleAnomalie(a.code) }}</p>
                            <p class="ui-meta">{{ a.message }}</p>
                          </div>
                          @if (a.ecritureId) { <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="ouvrirOperation(a.ecritureId)" data-action="ouvrir-operation">Traiter</button> }
                        </li>
                      }
                    </ul>
                  } @else {
                    <app-empty-state data-champ="aucune-anomalie" titre="Aucune anomalie" detail="Les contrôles fiscaux de cette période sont au vert." />
                  }
                }
              </section>
            }
            @case ('DECLARATION') {
              <section data-zone="declaration">
                @if (declaration(); as d) {
                  <app-section-header titre="Déclaration de TVA" [sousTitre]="d.officialStructure ? 'Structure fournie par le cadre fiscal ' + (d.framework?.version ?? '') : 'Structure générique interne, non officielle : les cases du formulaire administratif ne sont pas reproduites.'" />
                  <div class="ui-surface">
                    <table class="ui-table">
                      <thead><tr><th>Code</th><th>Libellé</th><th class="ui-num">Montant</th></tr></thead>
                      <tbody>
                        @for (l of d.lines; track l.code) {
                          <tr [attr.data-ligne-declaration]="l.code" [class.total]="l.source === 'NET_VAT'"><td class="ui-code">{{ l.code }}</td><td>{{ l.label }}</td><td class="ui-num">{{ montant(l.amount) }}</td></tr>
                        }
                      </tbody>
                    </table>
                  </div>
                  <p class="ui-meta">{{ d.basis === 'FISCAL_VERIFIED' ? 'Montants calculés avec un cadre fiscal vérifié.' : 'Montants comptabilisés : déclaration non finalisable.' }}</p>
                  @if (d.status === 'FINAL') {
                    <p class="ui-notice ui-notice--success" data-zone="declaration-finale"><app-icon nom="lock" /><span>Déclaration finalisée — snapshot immuable <span class="ui-mono">{{ d.snapshotSha256 }}</span></span></p>
                  } @else if (d.reviewedAt) {
                    <p class="ui-meta" data-champ="revue-info">Revue effectuée.</p>
                  }
                }
              </section>
            }
          }
        </div>
      } @else if (chargement()) {
        <p class="ui-meta" role="status">Chargement de la TVA…</p>
      } @else {
        <app-empty-state data-champ="aucune-periode" icone="calendar" titre="Aucune période TVA" detail="Ouvrez une période pour calculer la TVA depuis la comptabilité." />
      }

      <ng-template #tableau let-cote="cote">
        <section [attr.data-zone]="cote === 'OUTPUT' ? 'ventes' : 'achats'">
          <div class="filtre-ligne" data-zone="filtres-operations">
            <label class="ui-sr-only" for="tva-recherche">Rechercher</label>
            <input id="tva-recherche" type="search" class="ui-input recherche" placeholder="Document, tiers, référence, montant…" [ngModel]="recherche()" (ngModelChange)="changerRecherche($event)" data-champ="recherche" />
            <label class="ui-sr-only" for="tva-traitement-filtre">Traitement</label>
            <select id="tva-traitement-filtre" class="ui-input" [ngModel]="traitementFiltre()" (ngModelChange)="changerTraitement($event)" data-champ="filtre-traitement">
              <option value="">Tous les traitements</option>
              @for (t of traitementsFiltrables; track t) { <option [value]="t">{{ libelleTraitement(t) }}</option> }
            </select>
            <label class="ui-sr-only" for="tva-tri">Trier par</label>
            <select id="tva-tri" class="ui-input" [ngModel]="tri()" (ngModelChange)="changerTri($event)" data-champ="tri">
              <option value="date">Date</option><option value="document">Document</option><option value="amount">Montant</option><option value="status">Statut</option><option value="tiers">Tiers</option>
            </select>
            <button type="button" class="ui-btn ui-btn--tertiary ui-btn--sm" (click)="inverserSens()" data-action="sens-tri" [attr.aria-label]="'Ordre ' + (sens() === 'asc' ? 'croissant' : 'décroissant')">{{ sens() === 'asc' ? '↑' : '↓' }}</button>
            <label class="case"><input type="checkbox" [ngModel]="seulementARevoir()" (ngModelChange)="basculerARevoir($event)" data-champ="a-revoir-seulement" /> À revoir seulement</label>
            <label class="case"><input type="checkbox" [ngModel]="constatActif()" (ngModelChange)="basculerConstat($event)" data-champ="constat-actif" /> Constat actif</label>
          </div>
          @if (selectionGroupe().size && periode()?.permissions?.prepare && periode()?.status !== 'FINAL') {
            <div class="ui-surface formulaire" data-zone="validation-groupe">
              <p class="ui-meta plein" data-champ="nb-selection">{{ selectionGroupe().size }} opération(s) sélectionnée(s). Le serveur revalide chaque opération : seul un traitement certain, imposé par le sens de la TVA comptabilisée, se valide en masse.</p>
              <div class="ui-field plein"><label class="ui-field__label" for="groupe-motif">Justification<span class="ui-required" aria-hidden="true">*</span></label>
                <input id="groupe-motif" class="ui-input" [ngModel]="motifGroupe()" (ngModelChange)="motifGroupe.set($event)" data-champ="motif-groupe" /></div>
              <div class="actions-formulaire">
                <button type="button" class="ui-btn ui-btn--primary" [disabled]="!motifGroupe().trim() || enCours()" (click)="validerGroupe(cote)" data-action="valider-groupe">Valider comme {{ cote === 'OUTPUT' ? 'TVA collectée' : 'TVA déductible' }}</button>
                <button type="button" class="ui-btn ui-btn--tertiary" (click)="selectionGroupe.set(emptySet)">Désélectionner</button>
              </div>
            </div>
          }
          @if (page(); as pg) {
            @if (pg.items.length) {
              <div class="ui-surface">
                <table class="ui-table table-tva">
                  <thead>
                    <tr>
                      @if (periode()?.permissions?.prepare && periode()?.status !== 'FINAL') { <th class="col-select"><span class="ui-sr-only">Sélection</span></th> }
                      <th>Date</th><th>Document</th><th>{{ cote === 'OUTPUT' ? 'Client' : 'Fournisseur' }}</th><th class="ui-num">Base</th><th class="ui-num">Taux</th>
                      <th class="ui-num">{{ cote === 'OUTPUT' ? 'TVA' : 'TVA comptable' }}</th>
                      @if (cote === 'INPUT') { <th class="ui-num">TVA déductible</th> }
                      <th>Traitement fiscal</th><th>Statut</th><th class="col-action"><span class="ui-sr-only">Action</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (l of pg.items; track l.ecritureId) {
                      <tr [attr.data-ligne]="l.ecritureId" [class.a-revoir]="l.status === 'TO_REVIEW'">
                        @if (periode()?.permissions?.prepare && periode()?.status !== 'FINAL') {
                          <td class="col-select"><input type="checkbox" [checked]="selectionGroupe().has(l.ecritureId)" (change)="basculerSelection(l.ecritureId)" [attr.aria-label]="'Sélectionner ' + (l.document || l.piece)" data-champ="selection-ligne" /></td>
                        }
                        <td class="ui-muted">{{ l.date }}</td>
                        <td>{{ l.document || l.piece || '—' }}</td>
                        <td>{{ l.tiers || '—' }}</td>
                        <td class="ui-num">{{ montant(l.base) }}</td>
                        <td class="ui-num ui-muted">{{ l.rateCode ?? (l.impliedRatePercent === null ? '—' : taux(l.impliedRatePercent)) }}</td>
                        <td class="ui-num">{{ montant(l.vatAccounted) }}</td>
                        @if (cote === 'INPUT') { <td class="ui-num">{{ l.vatDeductible === null ? '—' : montant(l.vatDeductible) }}</td> }
                        <td>{{ libelleTraitement(l.treatment) }}@if (l.treatmentSource === 'DECISION') { <span class="ui-muted"> · décidé</span> }</td>
                        <td>@if (l.status === 'TO_REVIEW') { <app-status statut="WARNING" [texte]="libelleLigne(l)" /> } @else { <app-status statut="OK" texte="OK" /> }</td>
                        <td class="col-action">
                          <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="ouvrirOperation(l.ecritureId)" data-action="traiter">{{ l.status === 'TO_REVIEW' ? 'Traiter' : 'Détail' }}</button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              <div class="pagination">
                <span class="ui-meta" data-champ="pagination-info">{{ pg.page * pg.taille + 1 }}–{{ min(pg.page * pg.taille + pg.taille, pg.total) }} sur {{ pg.total }} · page {{ pg.page + 1 }} / {{ nombrePages(pg) }}</span>
                <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" [disabled]="pg.page === 0" (click)="changerPage(pg.page - 1)">Précédent</button>
                <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" [disabled]="(pg.page + 1) * pg.taille >= pg.total" (click)="changerPage(pg.page + 1)">Suivant</button>
              </div>
            } @else {
              <app-empty-state data-champ="aucune-operation" icone="inbox" [titre]="seulementARevoir() ? 'Aucune opération à revoir' : 'Aucune opération'" [detail]="seulementARevoir() ? 'Tout est traité pour ce côté.' : 'Aucune écriture validée ne touche la TVA sur cette période.'" />
            }
          }
          @if (selection(); as o) {
            <aside class="ui-surface traitement" data-zone="traitement">
              <h3 class="ui-section-title">{{ o.operation.document || o.operation.piece || 'Opération' }}@if (o.operation.tiers) { · {{ o.operation.tiers }} }</h3>
              <p class="ui-meta">Base {{ montant(o.operation.base) }} · TVA comptabilisée {{ montant(o.operation.vatAccounted) }}
                @if (o.operation.vatFiscal !== null) { · TVA calculée {{ montant(o.operation.vatFiscal) }} }</p>
              @for (i of o.operation.issues; track i) { <p class="ui-notice"><app-icon nom="alert-triangle" /><span>{{ libelleAnomalie(i) }}</span></p> }
              <h4 class="ui-eyebrow">Origine</h4>
              <table class="ui-table origine" data-zone="origine">
                <thead><tr><th>Compte</th><th>Libellé</th><th class="ui-num">Débit</th><th class="ui-num">Crédit</th></tr></thead>
                <tbody>@for (e of o.ecritureLignes; track $index) { <tr><td>{{ e.compte }}</td><td>{{ e.intitule }}</td><td class="ui-num">{{ montant(e.debit) }}</td><td class="ui-num">{{ montant(e.credit) }}</td></tr> }</tbody>
              </table>
              @if (o.facture) { <p class="ui-meta">Facture {{ o.facture['numero'] }} ({{ o.facture['statut'] }})</p> }
              @if (periode()?.permissions?.prepare && periode()?.status !== 'FINAL') {
                <h4 class="ui-eyebrow">Décision fiscale</h4>
                <div class="decision">
                  <div class="ui-field"><label class="ui-field__label" for="tv-traitement">Traitement</label>
                    <select id="tv-traitement" class="ui-input" [ngModel]="decision.treatment" (ngModelChange)="decision.treatment = $event" data-champ="traitement-select">
                      <option value="">Choisir…</option>
                      @for (t of options(o.operation.side); track t) { <option [value]="t">{{ libelleTraitement(t) }}</option> }
                    </select></div>
                  @if (decision.treatment === 'PARTIALLY_DEDUCTIBLE') {
                    <div class="ui-field"><label class="ui-field__label" for="tv-part">Part déductible (%)</label>
                      <input id="tv-part" type="number" class="ui-input" [ngModel]="decision.part" (ngModelChange)="decision.part = $event" data-champ="traitement-part" /></div>
                  }
                  <div class="ui-field plein"><label class="ui-field__label" for="tv-motif">Justification<span class="ui-required" aria-hidden="true">*</span></label>
                    <textarea id="tv-motif" rows="2" class="ui-input" [ngModel]="decision.reason" (ngModelChange)="decision.reason = $event" data-champ="traitement-motif"></textarea>
                    <span class="ui-field__hint">Conservée dans la piste d’audit avec la décision.</span></div>
                  <div class="actions-formulaire">
                    <button type="button" class="ui-btn ui-btn--primary" [disabled]="!decisionValide() || enCours()" (click)="enregistrerDecision(o.operation.ecritureId)" data-action="enregistrer-traitement">Enregistrer la décision</button>
                    <button type="button" class="ui-btn ui-btn--tertiary" (click)="selection.set(null)">Fermer</button>
                  </div>
                </div>
              } @else {
                <button type="button" class="ui-btn ui-btn--tertiary" (click)="selection.set(null)">Fermer</button>
              }
            </aside>
          }
        </section>
      </ng-template>
    </div>
  `,
  styles: [
    `
      .page-tva { max-width: var(--ui-page-max); margin: 0 auto; display: flex; flex-direction: column; gap: var(--ui-s4); }
      .entete-page { display: flex; justify-content: space-between; align-items: flex-end; gap: var(--ui-s4); flex-wrap: wrap; }
      .entete-page p { margin: var(--ui-s1) 0 0; }
      .selecteur { display: flex; gap: var(--ui-s2); align-items: center; }
      .selecteur .ui-input { width: auto; }
      .formulaire { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--ui-s3) var(--ui-s4); padding: var(--ui-s4); align-items: end; }
      .formulaire .ui-field { margin: 0; }
      .plein { grid-column: 1 / -1; }
      .actions-formulaire, .actions-confirmation { display: flex; gap: var(--ui-s2); align-items: center; }
      .statut-ligne { display: flex; align-items: center; gap: var(--ui-s4); flex-wrap: wrap; }
      .statut-ligne .actions { margin-left: auto; display: flex; gap: var(--ui-s2); align-items: center; }
      .contenu { padding-top: var(--ui-s2); }
      .resume { display: flex; flex-wrap: wrap; gap: var(--ui-s6); margin: 0; }
      .resume__item { display: flex; flex-direction: column; gap: var(--ui-s1); }
      .resume__item dt { font-size: var(--ui-fs-xs); font-weight: var(--ui-fw-semibold); letter-spacing: 0.04em; text-transform: uppercase; color: var(--ui-text-3); }
      .resume__item dd { margin: 0; font-size: var(--ui-fs-2xl); font-weight: var(--ui-fw-semibold); font-variant-numeric: tabular-nums; }
      .resume__item--net dd { border-bottom: 2px solid var(--ui-text); }
      .base { margin: var(--ui-s3) 0 0; }
      .rapprochement { max-width: 640px; }
      .liste-simple { margin: 0; padding-left: var(--ui-s5); color: var(--ui-text-2); }
      .filtre-ligne { display: flex; justify-content: flex-end; margin-bottom: var(--ui-s2); }
      .recherche { min-width: 220px; }
      .filtre-ligne { flex-wrap: wrap; gap: var(--ui-s2); align-items: center; }
      .filtre-ligne .ui-input { width: auto; }
      .col-select { width: 1%; }
      .etapes { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: var(--ui-s2) var(--ui-s5); }
      .historique { margin: var(--ui-s3) 0; }
      .case { display: inline-flex; align-items: center; gap: var(--ui-s2); font-size: var(--ui-fs-sm); color: var(--ui-text-2); }
      .table-tva tr.a-revoir td:first-child { box-shadow: inset 2px 0 0 var(--ui-warning); }
      tr.annule td { color: var(--ui-text-3); text-decoration: line-through; }
      tr.annule td:last-child, tr.annule td:nth-child(4) { text-decoration: none; }
      .col-action { width: 1%; white-space: nowrap; text-align: right; }
      .pagination { display: flex; justify-content: flex-end; align-items: center; gap: var(--ui-s2); margin-top: var(--ui-s3); }
      .traitement { margin-top: var(--ui-s5); padding: var(--ui-s4); }
      .traitement h3 { margin: 0; }
      .traitement .ui-meta { margin: var(--ui-s1) 0 var(--ui-s3); }
      .traitement .ui-eyebrow { margin: var(--ui-s4) 0 var(--ui-s2); }
      .decision { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--ui-s3) var(--ui-s4); }
      .decision .ui-field { margin: 0; }
      .inbox { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--ui-line); }
      .item { display: grid; grid-template-columns: auto 1fr auto; gap: var(--ui-s3); align-items: start; padding: var(--ui-s4); border-bottom: 1px solid var(--ui-line); background: var(--ui-surface); box-shadow: inset 2px 0 0 var(--ui-danger); }
      .item__icone { color: var(--ui-danger); padding-top: 2px; display: inline-flex; }
      .item__titre { margin: 0; font-weight: var(--ui-fw-medium); }
      .item .ui-meta { margin: 2px 0 0; }
    `,
  ],
})
export class TvaComponent implements OnInit {
  private readonly service = inject(TvaService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), { initialValue: '' });
  readonly onglets = ONGLETS;
  readonly genres = GENRES;
  readonly onglet = signal<Onglet>('VUE');
  readonly periodes = signal<PeriodeResume[]>([]);
  readonly periodeId = signal<string | null>(null);
  readonly periode = signal<Periode | null>(null);
  readonly page = signal<Page<LigneTva> | null>(null);
  readonly pageIndex = signal(0);
  readonly seulementARevoir = signal(false);
  readonly constatActif = signal(false);
  readonly recherche = signal('');
  readonly traitementFiltre = signal('');
  readonly tri = signal<FiltresOperations['tri']>('date');
  readonly sens = signal<'asc' | 'desc'>('asc');
  readonly selectionGroupe = signal<Set<string>>(new Set());
  readonly emptySet = new Set<string>();
  readonly motifGroupe = signal('');
  readonly conflit = signal(false);
  readonly formulaireCredit = signal(false);
  readonly paiementAAnnuler = signal<string | null>(null);
  readonly motifPaiement = signal('');
  readonly traitementsFiltrables: Traitement[] = ['OUTPUT_TAX', 'INPUT_TAX', 'EXEMPT', 'OUT_OF_SCOPE', 'NON_DEDUCTIBLE', 'PARTIALLY_DEDUCTIBLE', 'TO_REVIEW'];
  private minuteur: ReturnType<typeof setTimeout> | null = null;
  private cleIdempotence = TvaComponent.nouvelleCle();
  readonly ventesARevoir = signal(0);
  readonly ajustements = signal<Ajustement[]>([]);
  readonly controles = signal<Controles | null>(null);
  readonly declaration = signal<Declaration | null>(null);
  readonly selection = signal<Origine | null>(null);
  readonly operationsPourAjustement = signal<LigneTva[]>([]);
  readonly formulairePeriode = signal(false);
  readonly formulaireAjustement = signal(false);
  readonly confirmationFinal = signal(false);
  readonly chargement = signal(false);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly ajustementsActifs = computed(() => this.ajustements().filter((a) => !a.cancelled).length);

  nouvelle = { debut: '', fin: '' };
  paiement: { amount: number | null; date: string; reference: string; source: string } = { amount: null, date: new Date().toISOString().slice(0, 10), reference: '', source: 'MANUAL' };
  credit: { amount: number | null; reason: string; source: string } = { amount: null, reason: '', source: '' };
  decision: { treatment: string; part: number | null; reason: string } = { treatment: '', part: null, reason: '' };
  ajustement: { kind: string; effect: string; amount: number | null; reason: string; ecritureId: string | null } = { kind: 'CORRECTION', effect: 'OUTPUT', amount: null, reason: '', ecritureId: null };

  montant = formaterMontant;
  min = Math.min;

  ngOnInit(): void {
    this.chargerPeriodes();
  }

  libelleStatut(s: string): string {
    return ({ OPEN: 'Ouverte', PREPARING: 'En préparation', READY_FOR_REVIEW: 'Prête pour revue', REVIEWED: 'Revue', FINAL: 'Finalisée' } as Record<string, string>)[s] ?? s;
  }

  libelleOperationnel(s: StatutOperationnel): string {
    return STATUTS_OPERATIONNELS[s] ?? s;
  }

  libelleRaison(code: string): string {
    return RAISONS_BLOCAGE[code] ?? code;
  }

  libelleCredit(ouverture: string, cloture: string): string {
    if (ouverture === 'NONE' && cloture === 'NONE') {
      return 'Aucun crédit';
    }
    const o = ouverture === 'VALIDATED' ? 'ouverture validée' : ouverture === 'TO_REVIEW' ? 'ouverture à revoir' : '';
    const c = cloture === 'REPORTABLE' ? 'clôture reportable' : cloture === 'TO_REVIEW' ? 'clôture à revoir' : '';
    return [o, c].filter((x) => !!x).join(' · ');
  }

  nombrePages(pg: Page<LigneTva>): number {
    return Math.max(1, Math.ceil(pg.total / pg.taille));
  }

  private static nouvelleCle(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `cle-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  libelleTraitement(t: string): string {
    return TRAITEMENTS[t as Traitement] ?? t;
  }

  libelleAnomalie(code: string): string {
    return ANOMALIES[code] ?? code;
  }

  libelleGenre(code: string): string {
    return GENRES.find((g) => g.code === code)?.libelle ?? code;
  }

  libelleLigne(l: LigneTva): string {
    return l.issues.length ? this.libelleAnomalie(l.issues[0]) : 'À revoir';
  }

  options(cote: string): Traitement[] {
    return OPTIONS_PAR_COTE[cote] ?? OPTIONS_PAR_COTE['UNDETERMINED'];
  }

  taux(p: number): string {
    return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(p)} %`;
  }

  choisirPeriode(id: string): void {
    this.periodeId.set(id);
    this.selection.set(null);
    this.chargerPeriode();
  }

  ouvrirOnglet(o: Onglet): void {
    this.onglet.set(o);
    this.selection.set(null);
    this.selectionGroupe.set(this.emptySet);
    this.pageIndex.set(0);
    this.chargerOnglet();
  }

  private versPage1(): void {
    this.pageIndex.set(0);
    this.chargerOnglet();
  }

  changerRecherche(valeur: string): void {
    this.recherche.set(valeur ?? '');
    if (this.minuteur) {
      clearTimeout(this.minuteur);
    }
    this.minuteur = setTimeout(() => this.versPage1(), 300);                    // la recherche est envoyée au serveur après une courte pause
  }

  changerTraitement(valeur: string): void {
    this.traitementFiltre.set(valeur);
    this.versPage1();
  }

  changerTri(valeur: FiltresOperations['tri']): void {
    this.tri.set(valeur);
    this.versPage1();
  }

  inverserSens(): void {
    this.sens.update((s) => (s === 'asc' ? 'desc' : 'asc'));
    this.versPage1();
  }

  basculerConstat(valeur: boolean): void {
    this.constatActif.set(valeur);
    this.versPage1();
  }

  basculerSelection(id: string): void {
    this.selectionGroupe.update((s) => {
      const n = new Set(s);
      if (!n.delete(id)) {
        n.add(id);
      }
      return n;
    });
  }

  private filtres(cote: 'SALES' | 'PURCHASES'): Partial<FiltresOperations> {
    return { cote, statut: this.seulementARevoir() ? 'TO_REVIEW' : 'ALL', traitement: this.traitementFiltre(), constatActif: this.constatActif(), q: this.recherche(), tri: this.tri(), sens: this.sens() };
  }

  basculerARevoir(valeur: boolean): void {
    this.seulementARevoir.set(valeur);
    this.pageIndex.set(0);
    this.chargerOnglet();
  }

  changerPage(p: number): void {
    this.pageIndex.set(p);
    this.chargerOnglet();
  }

  ouvrirPeriode(): void {
    this.erreur.set(null);
    this.service.ouvrir(this.entrepriseId(), this.nouvelle.debut, this.nouvelle.fin).subscribe({
      next: (p) => {
        this.formulairePeriode.set(false);
        this.nouvelle = { debut: '', fin: '' };
        this.periodeId.set(p.id);
        this.chargerPeriodes(p.id);
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Période non ouverte.'),
    });
  }

  agir(action: 'recalculer' | 'preparer' | 'revoir' | 'rouvrir' | 'finaliser'): void {
    const id = this.periodeId();
    if (!id) {
      return;
    }
    this.enCours.set(true);
    this.effacerErreur();
    this.service.action(this.entrepriseId(), id, action, this.periode()?.version ?? null).subscribe({
      next: (p) => {
        this.periode.set(p);
        this.enCours.set(false);
        this.rafraichir();
      },
      error: (err) => {
        this.echec(err, 'Action impossible.');
        this.enCours.set(false);
        if (!this.conflit()) {
          this.chargerPeriode();
        }
      },
    });
  }

  ouvrirOperation(ecritureId: string): void {
    const id = this.periodeId();
    if (!id) {
      return;
    }
    this.decision = { treatment: '', part: null, reason: '' };
    if (this.onglet() === 'CONTROLES' || this.onglet() === 'VUE') {
      this.onglet.set('VENTES');
      this.pageIndex.set(0);
      this.chargerOnglet();
    }
    this.service.origine(this.entrepriseId(), id, ecritureId).subscribe({ next: (o) => this.selection.set(o), error: (err) => this.erreur.set(err.error?.message ?? 'Origine indisponible.') });
  }

  decisionValide(): boolean {
    return !!this.decision.treatment && !!this.decision.reason.trim() && (this.decision.treatment !== 'PARTIALLY_DEDUCTIBLE' || (this.decision.part ?? 0) > 0);
  }

  enregistrerDecision(ecritureId: string): void {
    const id = this.periodeId();
    if (!id || !this.decisionValide()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.service.decider(this.entrepriseId(), id, ecritureId, { treatment: this.decision.treatment, deductiblePercent: this.decision.treatment === 'PARTIALLY_DEDUCTIBLE' ? this.decision.part : null, reason: this.decision.reason.trim(), expectedVersion: this.periode()?.version ?? 0 }).subscribe({
      next: (p) => {
        this.periode.set(p);
        this.selection.set(null);
        this.enCours.set(false);
        this.rafraichir();
      },
      error: (err) => {
        this.echec(err, 'Décision refusée.');
        this.enCours.set(false);
      },
    });
  }

  ajustementValide(): boolean {
    return !!this.ajustement.amount && !!this.ajustement.reason.trim();
  }

  ajouterAjustement(): void {
    const id = this.periodeId();
    if (!id || !this.ajustementValide()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.service.ajouterAjustement(this.entrepriseId(), id, { kind: this.ajustement.kind, effect: this.ajustement.effect, amount: this.ajustement.amount!, reason: this.ajustement.reason.trim(), evidence: null, ecritureId: this.ajustement.ecritureId, expectedVersion: this.periode()?.version ?? 0 }).subscribe({
      next: (p) => {
        this.periode.set(p);
        this.ajustement = { kind: 'CORRECTION', effect: 'OUTPUT', amount: null, reason: '', ecritureId: null };
        this.formulaireAjustement.set(false);
        this.enCours.set(false);
        this.rafraichir();
      },
      error: (err) => {
        this.echec(err, 'Ajustement refusé.');
        this.enCours.set(false);
      },
    });
  }

  annulerAjustement(a: Ajustement): void {
    const id = this.periodeId();
    if (!id) {
      return;
    }
    this.effacerErreur();
    this.service.annulerAjustement(this.entrepriseId(), id, a.id, 'Annulé depuis l’espace TVA', this.periode()?.version ?? 0).subscribe({
      next: (p) => {
        this.periode.set(p);
        this.rafraichir();
      },
      error: (err) => this.echec(err, 'Annulation impossible.'),
    });
  }

  // ------------------------------------------------------------------------------------------------------------------------------ validation en masse, règlement, crédit

  validerGroupe(cote: 'OUTPUT' | 'INPUT'): void {
    const id = this.periodeId();
    const ids = Array.from(this.selectionGroupe());
    if (!id || !ids.length || !this.motifGroupe().trim()) {
      return;
    }
    this.enCours.set(true);
    this.effacerErreur();
    this.service.traitementGroupe(this.entrepriseId(), id, { ecritureIds: ids, treatment: cote === 'OUTPUT' ? 'OUTPUT_TAX' : 'INPUT_TAX', reason: this.motifGroupe().trim(), expectedVersion: this.periode()?.version ?? 0 }).subscribe({
      next: (r) => {
        this.periode.set(r.period);
        this.selectionGroupe.set(this.emptySet);
        this.motifGroupe.set('');
        this.enCours.set(false);
        this.rafraichir();
      },
      error: (err) => {
        this.echec(err, 'Validation en masse refusée.');
        this.enCours.set(false);
      },
    });
  }

  paiementValide(): boolean {
    return !!this.paiement.amount && this.paiement.amount > 0 && !!this.paiement.date;
  }

  enregistrerPaiement(): void {
    const id = this.periodeId();
    if (!id || !this.paiementValide()) {
      return;
    }
    this.enCours.set(true);
    this.effacerErreur();
    // la clé d'idempotence est conservée tant que l'envoi n'a pas réussi : un nouvel essai ne double jamais le paiement
    this.service.enregistrerPaiement(this.entrepriseId(), id, { amount: this.paiement.amount!, paymentDate: this.paiement.date, reference: this.paiement.reference.trim() || null, source: this.paiement.source,
      idempotencyKey: this.cleIdempotence, expectedVersion: this.periode()?.version ?? 0 }).subscribe({
      next: () => {
        this.cleIdempotence = TvaComponent.nouvelleCle();
        this.paiement = { amount: null, date: new Date().toISOString().slice(0, 10), reference: '', source: 'MANUAL' };
        this.enCours.set(false);
        this.chargerPeriode();
      },
      error: (err) => {
        this.echec(err, 'Paiement refusé.');
        this.enCours.set(false);
      },
    });
  }

  annulerPaiement(): void {
    const id = this.periodeId();
    const paiementId = this.paiementAAnnuler();
    if (!id || !paiementId || !this.motifPaiement().trim()) {
      return;
    }
    this.enCours.set(true);
    this.effacerErreur();
    this.service.annulerPaiement(this.entrepriseId(), id, paiementId, this.motifPaiement().trim(), this.periode()?.version ?? 0).subscribe({
      next: () => {
        this.paiementAAnnuler.set(null);
        this.motifPaiement.set('');
        this.enCours.set(false);
        this.chargerPeriode();
      },
      error: (err) => {
        this.echec(err, 'Annulation du paiement impossible.');
        this.enCours.set(false);
      },
    });
  }

  creditValide(): boolean {
    return !!this.credit.amount && this.credit.amount > 0 && !!this.credit.reason.trim() && !!this.credit.source.trim();
  }

  enregistrerCredit(): void {
    const id = this.periodeId();
    if (!id || !this.creditValide()) {
      return;
    }
    this.enCours.set(true);
    this.effacerErreur();
    this.service.saisirCredit(this.entrepriseId(), id, { amount: this.credit.amount!, reason: this.credit.reason.trim(), source: this.credit.source.trim(), previousPeriodId: null, expectedVersion: this.periode()?.version ?? 0 }).subscribe({
      next: () => {
        this.credit = { amount: null, reason: '', source: '' };
        this.formulaireCredit.set(false);
        this.enCours.set(false);
        this.chargerPeriode();
      },
      error: (err) => {
        this.echec(err, 'Crédit d’ouverture refusé.');
        this.enCours.set(false);
      },
    });
  }

  /** Conflit de version (409) : message de rechargement, jamais d'écrasement silencieux. */
  private echec(err: { status?: number; error?: { message?: string } }, defaut: string): void {
    this.conflit.set(err.status === 409 && /autre utilisateur/.test(err.error?.message ?? ''));
    this.erreur.set(this.conflit() ? 'Cette période a été modifiée par un autre utilisateur. Rechargez les dernières données avant de poursuivre.' : (err.error?.message ?? defaut));
  }

  private effacerErreur(): void {
    this.erreur.set(null);
    this.conflit.set(false);
  }

  /** Relit la période : les actions restent désactivées jusqu'à l'arrivée de la version courante (aucune mutation n'est envoyée avec une version périmée). */
  recharger(): void {
    const id = this.periodeId();
    this.effacerErreur();
    if (!id) {
      return;
    }
    this.enCours.set(true);
    this.service.periode(this.entrepriseId(), id).subscribe({
      next: (p) => {
        this.periode.set(p);
        this.enCours.set(false);
        this.rafraichirCompteurs();
        this.chargerOnglet();
      },
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Impossible de recharger la période.');
        this.enCours.set(false);
      },
    });
  }

  private chargerPeriodes(selection?: string): void {
    this.chargement.set(true);
    this.service.periodes(this.entrepriseId()).subscribe({
      next: (l) => {
        this.periodes.set(l);
        const courant = selection ?? this.periodeId() ?? l.find((p) => p.status !== 'FINAL')?.id ?? l[0]?.id ?? null;
        this.periodeId.set(courant);
        if (courant) {
          this.chargerPeriode();
        } else {
          this.periode.set(null);
          this.chargement.set(false);
        }
      },
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Impossible de charger la TVA.');
        this.chargement.set(false);
      },
    });
  }

  private chargerPeriode(): void {
    const id = this.periodeId();
    if (!id) {
      return;
    }
    this.service.periode(this.entrepriseId(), id).subscribe({
      next: (p) => {
        this.periode.set(p);
        this.chargement.set(false);
        this.rafraichirCompteurs();
        this.chargerOnglet();
      },
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Impossible de charger la période.');
        this.chargement.set(false);
      },
    });
  }

  private rafraichir(): void {
    this.chargerPeriodes(this.periodeId() ?? undefined);
  }

  private rafraichirCompteurs(): void {
    const id = this.periodeId();
    if (id) {
      this.service.operations(this.entrepriseId(), id, { cote: 'SALES', statut: 'TO_REVIEW' }, 0, 1).subscribe({ next: (p) => this.ventesARevoir.set(p.total), error: () => this.ventesARevoir.set(0) });
    }
  }

  private chargerOnglet(): void {
    const id = this.periodeId();
    if (!id) {
      return;
    }
    const e = this.entrepriseId();
    switch (this.onglet()) {
      case 'VENTES':
      case 'ACHATS':
        this.service.operations(e, id, this.filtres(this.onglet() === 'VENTES' ? 'SALES' : 'PURCHASES'), this.pageIndex(), 25).subscribe({ next: (p) => this.page.set(p), error: (err) => this.erreur.set(err.error?.message ?? 'Opérations indisponibles.') });
        break;
      case 'AJUSTEMENTS':
        this.service.ajustements(e, id).subscribe({ next: (a) => this.ajustements.set(a) });
        this.service.operations(e, id, { statut: 'ALL', tri: 'status', sens: 'asc' }, 0, 100).subscribe({ next: (ops) => this.operationsPourAjustement.set(ops.items) });
        break;
      case 'CONTROLES':
        this.service.controles(e, id).subscribe({ next: (c) => this.controles.set(c) });
        break;
      case 'DECLARATION':
        this.service.declaration(e, id).subscribe({ next: (d) => this.declaration.set(d) });
        break;
      default:
        break;
    }
  }
}
