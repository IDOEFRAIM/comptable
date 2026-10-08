import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { TokenStorageService } from './core/auth/token-storage.service';
import { authGuard } from './core/auth/auth.guard';
import { cabinetGuard } from './core/auth/cabinet.guard';
import { defaultTabRedirectGuard } from './core/auth/default-tab.guard';
import { modificationsNonEnregistreesGuard } from './core/forms/modifications-non-enregistrees.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'changer-mot-de-passe',
    canActivate: [() => !!inject(TokenStorageService).getToken() || inject(Router).parseUrl('/login')],
    loadComponent: () => import('./features/auth/changer-mot-de-passe.component').then((m) => m.ChangerMotDePasseComponent),
  },
  {
    path: 'reinitialiser-mot-de-passe',
    loadComponent: () => import('./features/auth/reinitialiser-mot-de-passe.component').then((m) => m.ReinitialiserMotDePasseComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./features/auth/register-cabinet.component').then((m) => m.RegisterCabinetComponent),
  },
  {
    // Depot d'une piece par le client, sans compte : le lien signe EST l'autorisation (voir DepotPublicController).
    path: 'depot/:jeton',
    loadComponent: () => import('./features/depot/depot-public.component').then((m) => m.DepotPublicComponent),
  },
  {
    path: '',
    loadComponent: () => import('./core/layout/shell.component').then((m) => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'onboarding/entreprise',
        loadComponent: () =>
          import('./features/entreprises/onboarding-wizard.component').then((m) => m.OnboardingWizardComponent),
      },
      {
        path: 'cabinet',
        canActivate: [cabinetGuard],
        loadComponent: () =>
          import('./features/cabinet-workspace/cabinet-workspace.component').then((m) => m.CabinetWorkspaceComponent),
      },
      {
        path: 'cabinet/dossiers',
        canActivate: [cabinetGuard],
        loadComponent: () => import('./features/cabinet-workspace/portfolio.component').then((m) => m.PortfolioComponent),
      },
      {
        path: 'cabinet/equipe',
        canActivate: [cabinetGuard],
        loadComponent: () => import('./features/cabinet-workspace/equipe.component').then((m) => m.EquipeComponent),
      },
      {
        path: 'cabinet/echeances',
        canActivate: [cabinetGuard],
        loadComponent: () => import('./features/cabinet-workspace/echeances.component').then((m) => m.EcheancesComponent),
      },
      {
        path: 'cabinet/cockpit',
        canActivate: [cabinetGuard],
        loadComponent: () =>
          import('./features/cabinet/cockpit-cabinet.component').then((m) => m.CockpitCabinetComponent),
      },
      {
        path: 'cabinet/indicateurs',
        canActivate: [cabinetGuard],
        loadComponent: () =>
          import('./features/telemetry/quality-dashboard.component').then((m) => m.QualityDashboardComponent),
      },
      {
        path: 'entreprises',
        loadComponent: () =>
          import('./features/entreprises/entreprise-list.component').then((m) => m.EntrepriseListComponent),
      },
      {
        path: 'entreprises/:entrepriseId',
        loadComponent: () =>
          import('./features/entreprises/entreprise-workspace.component').then(
            (m) => m.EntrepriseWorkspaceComponent,
          ),
        children: [
          { path: '', canActivate: [defaultTabRedirectGuard], children: [] },
          {
            path: 'tableau-de-bord',
            loadComponent: () =>
              import('./features/dashboard/tableau-de-bord.component').then((m) => m.TableauDeBordComponent),
          },
          {
            path: 'documents',
            loadComponent: () =>
              import('./features/documents/documents-page.component').then((m) => m.DocumentsPageComponent),
          },
          {
            path: 'revision',
            loadComponent: () =>
              import('./features/revision/revision.component').then((m) => m.RevisionComponent),
          },
          {
            path: 'demandes-pieces',
            loadComponent: () =>
              import('./features/demandes/demande-list.component').then((m) => m.DemandeListComponent),
          },
          {
            path: 'validation',
            loadComponent: () =>
              import('./features/comptabilite/validation/inbox-validation.component').then(
                (m) => m.InboxValidationComponent,
              ),
          },
          {
            path: 'tiers',
            loadComponent: () =>
              import('./features/comptabilite/tiers/tiers-list.component').then((m) => m.TiersListComponent),
          },
          {
            path: 'plan-comptable',
            loadComponent: () =>
              import('./features/comptabilite/plan-comptable/plan-comptable-page.component').then(
                (m) => m.PlanComptablePageComponent,
              ),
          },
          {
            path: 'parametres-comptables',
            loadComponent: () =>
              import('./features/comptabilite/parametres-comptables/parametres-comptables.component').then(
                (m) => m.ParametresComptablesComponent,
              ),
          },
          {
            path: 'operations',
            loadComponent: () =>
              import('./features/comptabilite/operations/operations-page.component').then(
                (m) => m.OperationsPageComponent,
              ),
          },
          {
            path: 'ecritures',
            loadComponent: () =>
              import('./features/comptabilite/ecritures/ecriture-list.component').then(
                (m) => m.EcritureListComponent,
              ),
          },
          {
            path: 'ecritures/nouvelle',
            canDeactivate: [modificationsNonEnregistreesGuard],
            loadComponent: () =>
              import('./features/comptabilite/ecritures/saisie-ecriture.component').then(
                (m) => m.SaisieEcritureComponent,
              ),
          },
          {
            path: 'rapports',
            loadComponent: () =>
              import('./features/comptabilite/rapports/rapports.component').then((m) => m.RapportsComponent),
          },
          {
            path: 'etats-financiers',
            loadComponent: () =>
              import('./features/dossier-financier/dossier-financier.component').then(
                (m) => m.DossierFinancierComponent,
              ),
          },
          {
            path: 'tresorerie',
            loadComponent: () =>
              import('./features/finance/tresorerie.component').then((m) => m.TresorerieComponent),
          },
          {
            path: 'recouvrement',
            loadComponent: () =>
              import('./features/recouvrement/recouvrement.component').then((m) => m.RecouvrementComponent),
          },
          {
            // Ecran Banque (Phase D) : transactions rapprochees, a confirmer, ambigues, sans correspondance ; affectation des reglements.
            path: 'rapprochement',
            loadComponent: () => import('./features/banque/banque.component').then((m) => m.BanqueComponent),
          },
          {
            // Ancien ecran releve par releve, conserve (propositions de l'IA, pointage ligne a ligne).
            path: 'rapprochement/classique',
            loadComponent: () =>
              import('./features/comptabilite/rapprochement/rapprochement-bancaire.component').then(
                (m) => m.RapprochementBancaireComponent,
              ),
          },
          {
            path: 'immobilisations',
            loadComponent: () =>
              import('./features/comptabilite/immobilisations/immobilisations.component').then(
                (m) => m.ImmobilisationsComponent,
              ),
          },
          {
            path: 'regularisations',
            loadComponent: () =>
              import('./features/comptabilite/regularisations/regularisations.component').then(
                (m) => m.RegularisationsComponent,
              ),
          },
          {
            path: 'facturation',
            loadComponent: () =>
              import('./features/comptabilite/facturation/facturation.component').then(
                (m) => m.FacturationComponent,
              ),
          },
          {
            path: 'stocks',
            loadComponent: () =>
              import('./features/comptabilite/stock/stock.component').then((m) => m.StockComponent),
          },
          {
            path: 'achats',
            loadComponent: () =>
              import('./features/comptabilite/stock/achats.component').then((m) => m.AchatsComponent),
          },
          {
            path: 'fiscalite/tva',
            loadComponent: () => import('./features/fiscalite-tva/tva.component').then((m) => m.TvaComponent),
          },
          {
            path: 'fiscalite',
            loadComponent: () =>
              import('./features/fiscalite/declaration-list.component').then((m) => m.DeclarationListComponent),
          },
          {
            path: 'conformite',
            loadComponent: () =>
              import('./features/conformite/conformite.component').then((m) => m.ConformiteComponent),
          },
          {
            path: 'inviter',
            loadComponent: () =>
              import('./features/entreprises/invitation/invitation.component').then((m) => m.InvitationComponent),
          },
        ],
      },
      { path: '', redirectTo: 'entreprises', pathMatch: 'full' },
    ],
  },
  { path: '**', redirectTo: 'entreprises' },
];
