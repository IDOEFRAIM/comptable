import { Component, EventEmitter, Output, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { DocumentService } from '../document.service';
import { DocumentDto, LIBELLES_TYPE_DOCUMENT, TypeDocument } from '../models/document.model';

const TAILLE_MAX_OCTETS = 10 * 1024 * 1024;
const TYPES_ACCEPTES = ['application/pdf', 'image/jpeg', 'image/png'];

@Component({
  selector: 'app-document-upload',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './document-upload.component.html',
  styleUrl: './document-upload.component.scss',
})
export class DocumentUploadComponent {
  entrepriseId = input.required<string>();

  @Output() documentDepose = new EventEmitter<DocumentDto>();

  private readonly fb = inject(FormBuilder);
  private readonly documentService = inject(DocumentService);

  readonly libellesType = LIBELLES_TYPE_DOCUMENT;
  readonly typesDocument = Object.keys(LIBELLES_TYPE_DOCUMENT) as TypeDocument[];

  readonly enSurvol = signal(false);
  readonly envoiEnCours = signal(false);
  readonly progression = signal(0);
  readonly erreur = signal<string | null>(null);
  readonly fichierSelectionne = signal<File | null>(null);

  readonly form = this.fb.nonNullable.group({
    typeDocument: this.fb.nonNullable.control<TypeDocument>('FACTURE_ACHAT', Validators.required),
  });

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.enSurvol.set(true);
  }

  onDragLeave(): void {
    this.enSurvol.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.enSurvol.set(false);
    const fichier = event.dataTransfer?.files?.[0];
    if (fichier) {
      this.selectionnerFichier(fichier);
    }
  }

  onFileInputChange(event: Event): void {
    const fichier = (event.target as HTMLInputElement).files?.[0];
    if (fichier) {
      this.selectionnerFichier(fichier);
    }
  }

  soumettre(): void {
    const fichier = this.fichierSelectionne();
    if (!fichier || this.form.invalid) {
      return;
    }

    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.progression.set(0);

    this.documentService
      .uploader(this.entrepriseId(), this.form.getRawValue().typeDocument, fichier)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (resultat) => {
          if (typeof resultat === 'number') {
            this.progression.set(resultat);
          } else {
            this.documentDepose.emit(resultat);
            this.fichierSelectionne.set(null);
          }
        },
        error: (err) => {
          this.erreur.set(err.error?.message ?? 'Échec du dépôt du document.');
        },
      });
  }

  private selectionnerFichier(fichier: File): void {
    this.erreur.set(null);

    if (!TYPES_ACCEPTES.includes(fichier.type)) {
      this.erreur.set('Type de fichier non supporté (PDF, JPEG ou PNG uniquement).');
      return;
    }
    if (fichier.size > TAILLE_MAX_OCTETS) {
      this.erreur.set('Le fichier dépasse 10 Mo.');
      return;
    }
    this.fichierSelectionne.set(fichier);
  }
}
