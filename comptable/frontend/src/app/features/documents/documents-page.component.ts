import { Component, ViewChild, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs/operators';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { CopilotDirigeantWidgetComponent } from '../copilot/copilot-dirigeant-widget.component';
import { DocumentUploadComponent } from './document-upload/document-upload.component';
import { DocumentListComponent } from './document-list/document-list.component';
import { DocumentHubComponent } from './document-hub/document-hub.component';
import { CollecteHubComponent } from '../demandes/collecte/collecte-hub.component';

/** Page "tableau de bord documents" d'une entreprise : depot + liste filtrable par statut. Pour
 * le role CLIENT (le dirigeant), c'est aussi la page d'atterrissage par defaut (voir
 * default-tab.guard.ts) - le Copilot Dirigeant (mission P1.5, section 46) y est donc integre
 * plutot que sur une page isolee. Absent pour le personnel du cabinet, qui a deja le Copilot
 * complet sur le Tableau de bord. */
@Component({
  selector: 'app-documents-page',
  standalone: true,
  imports: [DocumentUploadComponent, DocumentListComponent, DocumentHubComponent, CollecteHubComponent, CopilotDirigeantWidgetComponent],
  template: `
    @if (entrepriseId(); as entrepriseId) {
      <div class="page-documents">
        <header class="entete-documents">
          <h2>Documents</h2>
          <p>Déposez ici les factures, reçus et relevés : ils sont lus automatiquement puis rangés, ou envoyés en vérification s’il y a un doute.</p>
        </header>
        @if (!estMembreCabinet) {
          <app-copilot-dirigeant-widget [entrepriseId]="entrepriseId" />
        }
        <app-document-upload [entrepriseId]="entrepriseId" (documentDepose)="rafraichirListe()" />
        @if (estMembreCabinet) {
          <app-collecte-hub #collecte [entrepriseId]="entrepriseId" (modifie)="rafraichirListe()" (associer)="voirPieces()" />
          <app-document-hub #hub [entrepriseId]="entrepriseId" (modifie)="collecte?.rafraichir()" />
        } @else {
          <!-- Le dirigeant voit ses pieces ; rapprochement, doublons et validation sont le travail du cabinet. -->
          <app-document-list #liste [entrepriseId]="entrepriseId" />
        }
      </div>
    }
  `,
  styles: [
    `
      .page-documents {
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
      }
      .entete-documents h2 {
        margin: 0;
        font-size: 1.2rem;
      }
      .entete-documents p {
        margin: 0.3rem 0 0;
        color: var(--text-tertiary);
        font-size: 0.875rem;
      }
    `,
  ],
})
export class DocumentsPageComponent {
  @ViewChild('liste') liste?: DocumentListComponent;
  @ViewChild('hub') hub?: DocumentHubComponent;
  @ViewChild('collecte') collecte?: CollecteHubComponent;

  /** Une demande a reçu une pièce : le Hub documents est juste dessous, on y amène le comptable. */
  voirPieces(): void {
    document.querySelector('app-document-hub')?.scrollIntoView({ behavior: 'smooth' });
  }

  rafraichirListe(): void {
    this.liste?.rafraichir();
    this.hub?.rafraichir();
  }

  private readonly route = inject(ActivatedRoute);
  private readonly tokenStorage = inject(TokenStorageService);

  readonly entrepriseId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('entrepriseId'))),
    { initialValue: null },
  );

  readonly estMembreCabinet = this.tokenStorage
    .getRoles()
    .some((role) => role === 'ROLE_EXPERT_COMPTABLE' || role === 'ROLE_COLLABORATEUR');
}
