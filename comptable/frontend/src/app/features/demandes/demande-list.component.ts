import { Component, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { LIBELLES_TYPE_DOCUMENT, TypeDocument } from '../documents/models/document.model';
import { DemandeService } from './demande.service';
import { DemandePieceDto, LIBELLES_STATUT_DEMANDE } from './models/demande.model';

@Component({
  selector: 'app-demande-list',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './demande-list.component.html',
  styleUrl: './demande-list.component.scss',
})
export class DemandeListComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly demandeService = inject(DemandeService);
  private readonly tokenStorage = inject(TokenStorageService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesStatut = LIBELLES_STATUT_DEMANDE;
  readonly libellesType = LIBELLES_TYPE_DOCUMENT;
  readonly typesDocument = Object.keys(LIBELLES_TYPE_DOCUMENT) as TypeDocument[];

  readonly peutGerer = this.tokenStorage
    .getRoles()
    .some((role) => role === 'ROLE_EXPERT_COMPTABLE' || role === 'ROLE_COLLABORATEUR');

  readonly demandes = signal<DemandePieceDto[]>([]);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);
  readonly envoiEnCours = signal(false);

  readonly demandeEnReponse = signal<string | null>(null);
  readonly typeChoisi = signal<TypeDocument>('FACTURE_ACHAT');
  readonly fichierChoisi = signal<File | null>(null);

  readonly form = this.fb.nonNullable.group({
    libelle: ['', Validators.required],
    description: [''],
    dateLimite: [''],
  });

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.demandeService
      .lister(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (demandes) => this.demandes.set(demandes),
        error: () => this.erreur.set('Impossible de charger les demandes.'),
      });
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    const { libelle, description, dateLimite } = this.form.getRawValue();
    this.demandeService
      .creer(this.entrepriseId(), {
        libelle,
        description: description || undefined,
        dateLimite: dateLimite || undefined,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (demande) => {
          this.demandes.update((liste) => [demande, ...liste]);
          this.form.reset();
          this.afficherFormulaire.set(false);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer cette demande.'),
      });
  }

  annuler(demande: DemandePieceDto): void {
    this.demandeService.annuler(this.entrepriseId(), demande.id).subscribe({
      next: (maj) => this.remplacer(maj),
      error: (err) => this.erreur.set(err.error?.message ?? 'Action impossible.'),
    });
  }

  ouvrirReponse(demande: DemandePieceDto): void {
    this.demandeEnReponse.set(demande.id);
    this.fichierChoisi.set(null);
  }

  onFichierChoisi(event: Event): void {
    const fichier = (event.target as HTMLInputElement).files?.[0];
    this.fichierChoisi.set(fichier ?? null);
  }

  deposer(demande: DemandePieceDto): void {
    const fichier = this.fichierChoisi();
    if (!fichier) {
      return;
    }
    this.envoiEnCours.set(true);
    this.demandeService
      .repondre(this.entrepriseId(), demande.id, this.typeChoisi(), fichier)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (maj) => {
          this.remplacer(maj);
          this.demandeEnReponse.set(null);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Dépôt impossible.'),
      });
  }

  private remplacer(maj: DemandePieceDto): void {
    this.demandes.update((liste) => liste.map((d) => (d.id === maj.id ? maj : d)));
  }

  classeStatut(demande: DemandePieceDto): string {
    switch (demande.statut) {
      case 'RECU':
        return 'badge badge-recu';
      case 'ANNULEE':
        return 'badge badge-annulee';
      default:
        return 'badge badge-attente';
    }
  }
}
