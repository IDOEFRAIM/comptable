import { Component, ViewChild, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs/operators';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { CopilotDirigeantWidgetComponent } from '../copilot/copilot-dirigeant-widget.component';
import { DocumentUploadComponent } from './document-upload/document-upload.component';
import { DocumentListComponent } from './document-list/document-list.component';

/** Page "tableau de bord documents" d'une entreprise : depot + liste filtrable par statut. Pour
 * le role CLIENT (le dirigeant), c'est aussi la page d'atterrissage par defaut (voir
 * default-tab.guard.ts) - le Copilot Dirigeant (mission P1.5, section 46) y est donc integre
 * plutot que sur une page isolee. Absent pour le personnel du cabinet, qui a deja le Copilot
 * complet sur le Tableau de bord. */
@Component({
  selector: 'app-documents-page',
  standalone: true,
  imports: [DocumentUploadComponent, DocumentListComponent, CopilotDirigeantWidgetComponent],
  template: `
    @if (entrepriseId(); as entrepriseId) {
      <div class="page-documents">
        @if (!estMembreCabinet) {
          <app-copilot-dirigeant-widget [entrepriseId]="entrepriseId" />
        }
        <app-document-upload [entrepriseId]="entrepriseId" (documentDepose)="liste.rafraichir()" />
        <app-document-list #liste [entrepriseId]="entrepriseId" />
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
    `,
  ],
})
export class DocumentsPageComponent {
  @ViewChild('liste') liste!: DocumentListComponent;

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
