import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { cabinetGuard } from './core/auth/cabinet.guard';
import { defaultTabRedirectGuard } from './core/auth/default-tab.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./features/auth/register-cabinet.component').then((m) => m.RegisterCabinetComponent),
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
            path: 'ecritures',
            loadComponent: () =>
              import('./features/comptabilite/ecritures/ecriture-list.component').then(
                (m) => m.EcritureListComponent,
              ),
          },
          {
            path: 'ecritures/nouvelle',
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
              import('./features/comptabilite/etats-financiers/etats-financiers.component').then(
                (m) => m.EtatsFinanciersComponent,
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
            path: 'rapprochement',
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
