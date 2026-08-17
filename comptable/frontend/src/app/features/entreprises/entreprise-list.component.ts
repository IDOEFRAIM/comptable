import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { EntrepriseService } from './entreprise.service';
import { EntrepriseDto } from './models/entreprise.model';

@Component({
  selector: 'app-entreprise-list',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './entreprise-list.component.html',
  styleUrl: './entreprise-list.component.scss',
})
export class EntrepriseListComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly entrepriseService = inject(EntrepriseService);
  private readonly tokenStorage = inject(TokenStorageService);
  private readonly router = inject(Router);

  readonly entreprises = signal<EntrepriseDto[]>([]);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);
  readonly envoiEnCours = signal(false);

  readonly peutCreer = this.tokenStorage
    .getRoles()
    .some((role) => role === 'ROLE_EXPERT_COMPTABLE' || role === 'ROLE_COLLABORATEUR');

  readonly form = this.fb.nonNullable.group({
    raisonSociale: ['', Validators.required],
    pays: ['CI', [Validators.required, Validators.minLength(2), Validators.maxLength(2)]],
  });

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.entrepriseService
      .lister()
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (entreprises) => this.entreprises.set(entreprises),
        error: () => this.erreur.set('Impossible de charger les entreprises.'),
      });
  }

  ouvrir(entreprise: EntrepriseDto): void {
    this.router.navigate(['/entreprises', entreprise.id, 'documents']);
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.entrepriseService
      .creer(this.form.getRawValue())
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (entreprise) => {
          this.entreprises.update((liste) => [...liste, entreprise]);
          this.form.reset({ raisonSociale: '', pays: 'CI' });
          this.afficherFormulaire.set(false);
        },
        error: () => this.erreur.set("Impossible de créer l'entreprise."),
      });
  }
}
