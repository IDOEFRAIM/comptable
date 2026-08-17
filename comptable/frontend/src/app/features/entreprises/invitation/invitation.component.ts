import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { EntrepriseService } from '../entreprise.service';
import { RoleInvitable, UtilisateurInviteDto } from '../models/entreprise.model';

@Component({
  selector: 'app-invitation',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './invitation.component.html',
  styleUrl: './invitation.component.scss',
})
export class InvitationComponent {
  private readonly fb = inject(FormBuilder);
  private readonly entrepriseService = inject(EntrepriseService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly dernierInvite = signal<UtilisateurInviteDto | null>(null);

  readonly form = this.fb.nonNullable.group({
    role: this.fb.nonNullable.control<RoleInvitable>('CLIENT', Validators.required),
    email: ['', [Validators.required, Validators.email]],
    nom: ['', Validators.required],
    prenom: ['', Validators.required],
    telephone: [''],
  });

  inviter(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.dernierInvite.set(null);
    const { role, email, nom, prenom, telephone } = this.form.getRawValue();
    this.entrepriseService
      .inviter(this.entrepriseId(), { role, email, nom, prenom, telephone: telephone || undefined })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (invite) => {
          this.dernierInvite.set(invite);
          this.form.reset({ role: 'CLIENT', email: '', nom: '', prenom: '', telephone: '' });
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer ce compte.'),
      });
  }
}
