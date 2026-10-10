import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { InformationsDepotDto } from '../demandes/models/demande.model';

type Etat = 'CHARGEMENT' | 'PRET' | 'INVALIDE' | 'ENVOYE';

/**
 * Page publique de dépôt d'une pièce justificative, ouverte depuis le lien reçu par message. Aucun compte, aucune donnée interne :
 * on montre seulement ce qu'on demande (entreprise, opération, montant, date). La pièce suit exactement le même traitement qu'un
 * document déposé par le cabinet (analyse, rapprochement) ; un dépôt ne résout jamais la demande à lui seul.
 */
@Component({
  selector: 'app-depot-public',
  standalone: true,
  imports: [CurrencyPipe, DatePipe],
  template: `
    <main class="depot">
      <h1>Envoyer une pièce justificative</h1>

      @switch (etat()) {
        @case ('CHARGEMENT') {
          <p>Chargement…</p>
        }
        @case ('INVALIDE') {
          <p class="message-erreur" role="alert" data-etat="invalide">
            Ce lien n’est plus valable. Il a peut-être expiré, ou la pièce a déjà été reçue. Contactez votre cabinet si besoin.
          </p>
        }
        @case ('ENVOYE') {
          <p class="message-ok" role="status" data-etat="envoye">
            Merci, votre pièce a bien été envoyée. Votre cabinet s’en occupe : vous n’avez rien d’autre à faire.
          </p>
        }
        @default {
          @if (infos(); as i) {
            <section class="resume" data-bloc="resume">
              <p class="entreprise">{{ i.entreprise }}</p>
              <p>Votre cabinet a besoin du justificatif de l’opération suivante :</p>
              <p class="operation">{{ i.description }}</p>
              <ul>
                @if (i.montant !== null) {
                  <li>Montant : <strong>{{ i.montant | currency: 'XOF' : 'symbol' : '1.0-0' }}</strong></li>
                }
                @if (i.date) {
                  <li>Date : {{ i.date | date: 'dd/MM/yyyy' }}</li>
                }
                @if (i.dateLimite) {
                  <li>À fournir avant le {{ i.dateLimite | date: 'dd/MM/yyyy' }}</li>
                }
              </ul>
            </section>

            <label class="champ-fichier">
              <span>Choisir la photo ou le PDF de la facture</span>
              <input type="file" accept=".pdf,.jpg,.jpeg,.png" (change)="choisir($event)" data-champ="fichier" />
            </label>
            @if (fichier()) {
              <p class="meta">Fichier : {{ fichier()!.name }}</p>
            }
            @if (erreur()) {
              <p class="message-erreur" role="alert">{{ erreur() }}</p>
            }
            <button type="button" class="envoyer" [disabled]="!fichier() || envoi()" (click)="envoyer()" data-action="envoyer-piece">
              {{ envoi() ? 'Envoi…' : 'Envoyer' }}
            </button>
          }
        }
      }
    </main>
  `,
  styles: [
    `
      .depot { max-width: 34rem; margin: 0 auto; padding: 1.5rem 1rem 3rem; font-family: system-ui, sans-serif; }
      h1 { font-size: 1.35rem; }
      .entreprise { font-weight: 700; margin-bottom: 0.2rem; }
      .operation { font-size: 1.1rem; font-weight: 600; }
      .resume { background: #f6f8fb; border-radius: 10px; padding: 1rem; margin: 1rem 0; }
      .champ-fichier { display: flex; flex-direction: column; gap: 0.4rem; margin: 1rem 0; }
      .meta { color: #5b6475; font-size: 0.85rem; }
      .envoyer { width: 100%; padding: 0.9rem; font-size: 1rem; border: 0; border-radius: 8px; background: var(--color-primary, #14662f); color: #fff; }
      .envoyer:disabled { background: #a9b6dd; }
      .message-erreur { color: #b42318; }
      .message-ok { background: #e7f6ec; padding: 1rem; border-radius: 8px; }
    `,
  ],
})
export class DepotPublicComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  readonly etat = signal<Etat>('CHARGEMENT');
  readonly infos = signal<InformationsDepotDto | null>(null);
  readonly fichier = signal<File | null>(null);
  readonly envoi = signal(false);
  readonly erreur = signal<string | null>(null);

  private jeton = '';

  ngOnInit(): void {
    this.jeton = this.route.snapshot.paramMap.get('jeton') ?? '';
    this.http.get<InformationsDepotDto>(`/api/depot/${encodeURIComponent(this.jeton)}`).subscribe({
      next: (i) => {
        this.infos.set(i);
        this.etat.set('PRET');
      },
      // Toujours le meme ecran pour un lien forge, expire ou clos : rien ne distingue les cas.
      error: () => this.etat.set('INVALIDE'),
    });
  }

  choisir(event: Event): void {
    this.fichier.set((event.target as HTMLInputElement).files?.[0] ?? null);
    this.erreur.set(null);
  }

  envoyer(): void {
    const fichier = this.fichier();
    if (!fichier) {
      return;
    }
    const formData = new FormData();
    formData.append('fichier', fichier);
    this.envoi.set(true);
    this.erreur.set(null);
    this.http
      .post(`/api/depot/${encodeURIComponent(this.jeton)}`, formData)
      .pipe(finalize(() => this.envoi.set(false)))
      .subscribe({
        next: () => this.etat.set('ENVOYE'),
        error: (err) => {
          if (err.status === 404) {
            this.etat.set('INVALIDE');
          } else {
            this.erreur.set(err.error?.message ?? 'L’envoi a échoué. Vérifiez le fichier (PDF, JPG ou PNG) et réessayez.');
          }
        },
      });
  }
}
