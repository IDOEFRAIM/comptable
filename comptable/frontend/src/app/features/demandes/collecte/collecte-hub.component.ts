import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { DemandeService } from '../demande.service';
import {
  ApercuCollecteDto,
  DemandePieceDto,
  ElementCollecteDto,
  LIBELLES_STATUT_DEMANDE,
  MessageCollecteDto,
  SyntheseCollecteDto,
} from '../models/demande.model';

type Filtre = 'TOUTES' | 'A_ENVOYER' | 'EN_ATTENTE' | 'A_RELANCER' | 'ECHEC_ENVOI' | 'RECU' | 'CLOTUREES';

/**
 * Collecte active des pièces justificatives : ce qui manque, ce qui a été demandé, ce qui demande une action. Le serveur envoie, relance
 * et rapproche tout seul ; le comptable ne garde que l'exception. L'envoi groupé passe TOUJOURS par un aperçu calculé par le serveur.
 */
@Component({
  selector: 'app-collecte-hub',
  standalone: true,
  imports: [FormsModule, DatePipe, CurrencyPipe],
  template: `
    <section class="collecte">
      <header class="entete">
        <h3>Collecte des pièces</h3>
        <button type="button" class="bouton-lien" (click)="rafraichir()">Actualiser</button>
      </header>

      @if (info()) {
        <p class="message-info" role="status">{{ info() }}</p>
      }
      @if (erreur()) {
        <p class="message-erreur" role="alert">{{ erreur() }}</p>
      }

      @if (synthese(); as s) {
        <div class="tuiles">
          <button type="button" class="tuile" [class.active]="filtre() === 'TOUTES'" (click)="filtre.set('TOUTES')" data-tuile="manquantes">
            <strong>{{ s.piecesManquantes }}</strong><span>Pièces manquantes</span>
          </button>
          <div class="tuile" data-tuile="a-demander"><strong>{{ s.aDemander }}</strong><span>À demander</span></div>
          <button type="button" class="tuile" [class.active]="filtre() === 'A_ENVOYER'" (click)="filtre.set('A_ENVOYER')" data-tuile="a-envoyer">
            <strong>{{ s.aEnvoyer }}</strong><span>En boîte d'envoi</span>
          </button>
          <button type="button" class="tuile" [class.active]="filtre() === 'EN_ATTENTE'" (click)="filtre.set('EN_ATTENTE')" data-tuile="demandees">
            <strong>{{ s.enAttenteClient }}</strong><span>Demandées</span>
          </button>
          <button type="button" class="tuile" [class.active]="filtre() === 'A_RELANCER'" (click)="filtre.set('A_RELANCER')" data-tuile="a-relancer">
            <strong>{{ s.relancesNecessaires }}</strong><span>À relancer</span>
          </button>
          <button type="button" class="tuile tuile-alerte" [class.active]="filtre() === 'ECHEC_ENVOI'" (click)="filtre.set('ECHEC_ENVOI')" data-tuile="echecs">
            <strong>{{ s.echecsEnvoi + s.sansContact }}</strong><span>Échecs / sans contact</span>
          </button>
          <button type="button" class="tuile" [class.active]="filtre() === 'RECU'" (click)="filtre.set('RECU')" data-tuile="recues">
            <strong>{{ s.recues }}</strong><span>Reçues</span>
          </button>
        </div>
      }

      <!-- ------------------------------------------------------------------ opérations sans pièce : demande groupée -->
      @if (peutGerer()) {
        <div class="carte bloc-a-demander" data-bloc="a-demander">
          <div class="entete">
            <h4>Opérations sans pièce ({{ aDemander().length }})</h4>
            @if (aDemanderLibres().length > 0) {
              <label class="case-tout">
                <input type="checkbox" [checked]="toutSelectionne()" (change)="basculerTout()" /> Tout sélectionner
              </label>
            }
          </div>
          @if (aDemander().length === 0) {
            <p class="aide">Aucune opération sans justificatif : rien à demander.</p>
          }
          @for (e of aDemander(); track e.findingId) {
            <label class="ligne-element" [class.deja]="e.dejaDemande">
              <input type="checkbox" [disabled]="e.dejaDemande" [checked]="selection().has(e.findingId)" (change)="basculer(e.findingId)" />
              <span class="tiers">{{ e.tiers ?? 'Tiers non renseigné' }}</span>
              <span>{{ e.libelle }}</span>
              <span class="meta">{{ e.date ? (e.date | date: 'dd/MM/yyyy') : '' }}</span>
              <strong>{{ e.montant !== null ? (e.montant | currency: 'XOF' : 'symbol' : '1.0-0') : '' }}</strong>
              @if (e.dejaDemande) {
                <span class="badge badge-attente">déjà demandée</span>
              }
            </label>
          }
          @if (selection().size > 0) {
            <div class="actions">
              <button type="button" class="bouton-principal" [disabled]="occupe()" (click)="previsualiser()" data-action="apercu">
                Demander {{ selection().size }} pièce(s)…
              </button>
            </div>
          }

          @if (apercu(); as a) {
            <div class="apercu" data-bloc="apercu">
              <h4>Aperçu avant envoi</h4>
              <p>
                <strong>{{ a.elements.length - a.ignores > 0 ? a.elements.length - a.ignores : 0 }}</strong> pièce(s) à demander,
                <strong>{{ a.messages.length }}</strong> message(s) pour <strong>{{ a.messages.length }}</strong> destinataire(s).
                @if (a.ignores > 0) {
                  <span class="meta">{{ a.ignores }} déjà demandée(s), ignorée(s).</span>
                }
              </p>
              @if (a.blocage) {
                <p class="message-erreur">Envoi impossible : {{ a.blocage }}</p>
              }
              @for (m of a.messages; track m.telephone) {
                <div class="message-apercu">
                  <span class="meta">À {{ m.destinataire }} ({{ m.telephone }}) — {{ m.nbDemandes }} pièce(s)</span>
                  <pre>{{ m.texte }}</pre>
                </div>
              }
              <label class="case-immediat">
                <input type="checkbox" [(ngModel)]="envoyerMaintenant" /> Envoyer maintenant
                <span class="meta">(sinon les messages restent en boîte d'envoi jusqu'à la prochaine passe)</span>
              </label>
              <div class="actions">
                <button type="button" class="bouton-principal" [disabled]="occupe() || a.messages.length === 0" (click)="confirmer()" data-action="envoyer-groupe">
                  Confirmer la demande
                </button>
                <button type="button" class="bouton-lien" (click)="apercu.set(null)">Annuler</button>
              </div>
            </div>
          }
        </div>
      }

      <!-- ------------------------------------------------------------------------------------------ demandes -->
      <div class="carte" data-bloc="demandes">
        <div class="entete">
          <h4>Demandes ({{ visibles().length }})</h4>
          <select [ngModel]="filtre()" (ngModelChange)="filtre.set($event)" aria-label="Filtrer les demandes">
            <option value="TOUTES">Toutes</option>
            <option value="A_ENVOYER">En boîte d'envoi</option>
            <option value="EN_ATTENTE">Demandées</option>
            <option value="A_RELANCER">À relancer</option>
            <option value="ECHEC_ENVOI">Échecs d'envoi</option>
            <option value="RECU">Reçues</option>
            <option value="CLOTUREES">Closes</option>
          </select>
        </div>
        @if (chargement()) {
          <p class="meta">Chargement…</p>
        } @else if (visibles().length === 0) {
          <p class="aide">Aucune demande dans cette vue.</p>
        }
        @for (d of visibles(); track d.id) {
          <article class="demande" [attr.data-statut]="d.statut" [attr.data-demande]="d.id">
            <div class="ligne">
              <span class="tiers">{{ d.tiersNom ?? 'Tiers non renseigné' }}</span>
              <span>{{ d.descriptionOperation ?? d.libelle }}</span>
              <span class="meta">{{ d.dateOperation ? (d.dateOperation | date: 'dd/MM/yyyy') : '' }}</span>
              <strong>{{ d.montant !== null ? (d.montant | currency: 'XOF' : 'symbol' : '1.0-0') : '' }}</strong>
              <span class="badge" [class]="'badge ' + classeStatut(d)">{{ libelles[d.statut] }}</span>
            </div>
            <div class="suivi meta">
              @if (d.dernierMessage; as m) {
                Dernier message : {{ libelleMessage(m.statut) }}
                @if (m.envoyeLe) { le {{ m.envoyeLe | date: 'dd/MM HH:mm' }} }
                @if (m.statut === 'PENDING' && m.tentatives > 0) { · tentative {{ m.tentatives }} }
                @if (m.derniereErreur && m.statut !== 'SENT' && !close(d)) { · {{ m.derniereErreur }} }
              } @else {
                Aucun message envoyé
              }
              · {{ d.nbRelances }} relance(s)
              @if (d.prochaineRelanceAt && d.statut === 'EN_ATTENTE') { · prochaine relance le {{ d.prochaineRelanceAt | date: 'dd/MM' }} }
              @if (d.motifBlocage && !close(d)) { · <strong>{{ libelleMotif(d.motifBlocage) }}</strong> }
            </div>
            @if (peutGerer()) {
              <div class="actions">
                @if (d.statut === 'A_ENVOYER' || d.statut === 'ECHEC_ENVOI') {
                  <button type="button" class="bouton-lien" (click)="envoyer(d)" data-action="envoyer">Envoyer</button>
                }
                @if (d.statut === 'EN_ATTENTE') {
                  <button type="button" class="bouton-lien" (click)="relancer(d)" data-action="relancer">Relancer</button>
                }
                @if (d.statut === 'RECU') {
                  <button type="button" class="bouton-lien" (click)="associer.emit(d)" data-action="associer">Voir la pièce reçue</button>
                }
                @if (d.statut === 'A_ENVOYER' || d.statut === 'EN_ATTENTE' || d.statut === 'ECHEC_ENVOI' || d.statut === 'RECU') {
                  <button type="button" class="bouton-lien bouton-discret" (click)="annuler(d)" data-action="annuler">Annuler</button>
                }
                <button type="button" class="bouton-lien" (click)="basculerHistorique(d)">Historique</button>
              </div>
              @if (historique()[d.id]; as msgs) {
                <ul class="historique">
                  @for (m of msgs; track m.id) {
                    <li>
                      {{ m.cree | date: 'dd/MM HH:mm' }} — {{ m.type === 'REMINDER' ? 'Relance' : 'Demande' }} : {{ libelleMessage(m.statut) }}
                      @if (m.tentatives > 1) { ({{ m.tentatives }} tentatives) }
                      @if (m.derniereErreur && m.statut !== 'SENT') { — {{ m.derniereErreur }} }
                    </li>
                  }
                </ul>
              }
            }
          </article>
        }
      </div>
    </section>
  `,
  styles: [
    `
      .collecte { display: flex; flex-direction: column; gap: 1rem; }
      .entete { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
      .tuiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 0.6rem; }
      .tuile { display: flex; flex-direction: column; align-items: flex-start; gap: 0.1rem; padding: 0.6rem 0.8rem; border: 1px solid #d6dbe4; border-radius: 8px; background: #fff; font: inherit; text-align: left; }
      button.tuile { cursor: pointer; }
      .tuile strong { font-size: 1.4rem; }
      .tuile span { font-size: 0.8rem; color: #5b6475; }
      .tuile.active { border-color: #2f5be7; box-shadow: 0 0 0 1px #2f5be7; }
      .tuile-alerte strong { color: #b42318; }
      .ligne-element, .ligne { display: flex; flex-wrap: wrap; align-items: center; gap: 0.6rem; padding: 0.45rem 0; border-bottom: 1px solid #eef0f4; }
      .ligne-element.deja { opacity: 0.55; }
      .tiers { font-weight: 600; min-width: 9rem; }
      .meta { color: #5b6475; font-size: 0.82rem; }
      .actions { display: flex; flex-wrap: wrap; gap: 0.6rem; margin-top: 0.4rem; align-items: center; }
      .apercu { margin-top: 1rem; padding-top: 0.8rem; border-top: 2px solid #2f5be7; }
      .message-apercu pre { white-space: pre-wrap; background: #f6f8fb; padding: 0.6rem; border-radius: 6px; font-family: inherit; margin: 0.3rem 0 0.6rem; }
      .demande { padding: 0.6rem 0; border-bottom: 1px solid #eef0f4; }
      .historique { margin: 0.4rem 0 0; padding-left: 1.2rem; font-size: 0.82rem; color: #5b6475; }
      .message-info { background: #e8f1ff; padding: 0.5rem 0.8rem; border-radius: 6px; }
      .message-erreur { color: #b42318; }
      .case-immediat { display: flex; align-items: center; gap: 0.4rem; margin: 0.6rem 0; }
      select { padding: 0.3rem 0.5rem; }
    `,
  ],
})
export class CollecteHubComponent implements OnInit {
  entrepriseId = input.required<string>();
  peutGerer = input<boolean>(true);
  /** Le comptable veut rattacher la pièce reçue d'une demande : le Hub documents ouvre son panneau d'association. */
  associer = output<DemandePieceDto>();
  /** Émis quand la collecte a changé l'état de pièces/constats (le Hub documents se recharge). */
  modifie = output<void>();

  private readonly service = inject(DemandeService);

  readonly libelles = LIBELLES_STATUT_DEMANDE;
  readonly synthese = signal<SyntheseCollecteDto | null>(null);
  readonly aDemander = signal<ElementCollecteDto[]>([]);
  readonly demandes = signal<DemandePieceDto[]>([]);
  readonly selection = signal<Set<string>>(new Set());
  readonly apercu = signal<ApercuCollecteDto | null>(null);
  readonly historique = signal<Record<string, MessageCollecteDto[]>>({});
  readonly filtre = signal<Filtre>('TOUTES');
  readonly chargement = signal(false);
  readonly occupe = signal(false);
  readonly info = signal<string | null>(null);
  readonly erreur = signal<string | null>(null);
  envoyerMaintenant = true;

  readonly aDemanderLibres = computed(() => this.aDemander().filter((e) => !e.dejaDemande));
  readonly toutSelectionne = computed(
    () => this.aDemanderLibres().length > 0 && this.aDemanderLibres().every((e) => this.selection().has(e.findingId)),
  );
  readonly visibles = computed(() => {
    const maintenant = Date.now();
    return this.demandes().filter((d) => {
      switch (this.filtre()) {
        case 'A_ENVOYER':
          return d.statut === 'A_ENVOYER';
        case 'EN_ATTENTE':
          return d.statut === 'EN_ATTENTE';
        case 'A_RELANCER':
          return d.statut === 'EN_ATTENTE' && !!d.prochaineRelanceAt && new Date(d.prochaineRelanceAt).getTime() <= maintenant;
        case 'ECHEC_ENVOI':
          return d.statut === 'ECHEC_ENVOI';
        case 'RECU':
          return d.statut === 'RECU';
        case 'CLOTUREES':
          return d.statut === 'RAPPROCHEE' || d.statut === 'ANNULEE' || d.statut === 'EXPIREE';
        default:
          return true;
      }
    });
  });

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    const id = this.entrepriseId();
    this.service.syntheseCollecte(id).subscribe({ next: (s) => this.synthese.set(s), error: () => this.erreur.set('Synthèse indisponible.') });
    this.service.aDemander(id).subscribe({
      next: (liste) => {
        this.aDemander.set(liste);
        // Une sélection ne survit jamais à une opération devenue « déjà demandée » entre-temps.
        this.selection.update((sel) => new Set([...sel].filter((f) => liste.some((e) => e.findingId === f && !e.dejaDemande))));
      },
      error: () => this.erreur.set('Impossible de charger les opérations sans pièce.'),
    });
    this.service
      .listerCollecte(id)
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({ next: (d) => this.demandes.set(d), error: () => this.erreur.set('Impossible de charger les demandes.') });
  }

  basculer(findingId: string): void {
    this.apercu.set(null);
    this.selection.update((sel) => {
      const copie = new Set(sel);
      if (!copie.delete(findingId)) {
        copie.add(findingId);
      }
      return copie;
    });
  }

  basculerTout(): void {
    this.apercu.set(null);
    this.selection.set(this.toutSelectionne() ? new Set() : new Set(this.aDemanderLibres().map((e) => e.findingId)));
  }

  /** Le serveur recalcule tout : combien de pièces, combien de messages, à qui, avec quel texte. Rien n'est créé. */
  previsualiser(): void {
    this.occupe.set(true);
    this.erreur.set(null);
    this.service
      .apercuGroupe(this.entrepriseId(), [...this.selection()])
      .pipe(finalize(() => this.occupe.set(false)))
      .subscribe({
        next: (a) => this.apercu.set(a),
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de calculer l’aperçu.'),
      });
  }

  confirmer(): void {
    this.occupe.set(true);
    this.service
      .demanderGroupe(this.entrepriseId(), [...this.selection()], this.envoyerMaintenant)
      .pipe(finalize(() => this.occupe.set(false)))
      .subscribe({
        next: (r) => {
          this.apercu.set(null);
          this.selection.set(new Set());
          this.info.set(
            `${r.demandeIds.length} demande(s) créée(s), ${r.messageIds.length} message(s) en boîte d'envoi` +
              (r.ignores > 0 ? `, ${r.ignores} déjà demandée(s) ignorée(s)` : '') +
              (r.blocage ? `. Envoi impossible : ${r.blocage}` : '.'),
          );
          this.rafraichir();
          this.rafraichirPlusTard();
          this.modifie.emit();
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer les demandes.'),
      });
  }

  envoyer(d: DemandePieceDto): void {
    this.agir(this.service.envoyer(this.entrepriseId(), d.id), 'Demande remise en boîte d’envoi.');
  }

  relancer(d: DemandePieceDto): void {
    this.agir(this.service.relancer(this.entrepriseId(), d.id), 'Relance mise en boîte d’envoi.');
  }

  annuler(d: DemandePieceDto): void {
    this.agir(this.service.annuler(this.entrepriseId(), d.id), 'Demande annulée.');
  }

  basculerHistorique(d: DemandePieceDto): void {
    if (this.historique()[d.id]) {
      this.historique.update((h) => {
        const { [d.id]: _retire, ...reste } = h;
        return reste;
      });
      return;
    }
    this.service.messages(this.entrepriseId(), d.id).subscribe({
      next: (m) => this.historique.update((h) => ({ ...h, [d.id]: m })),
      error: () => this.erreur.set('Historique indisponible.'),
    });
  }

  /** L'envoi part en arrière-plan : on relit l'état une seconde fois pour montrer son issue réelle. */
  private rafraichirPlusTard(): void {
    setTimeout(() => this.rafraichir(), 3000);
  }

  private agir(appel: ReturnType<DemandeService['envoyer']>, message: string): void {
    this.erreur.set(null);
    appel.subscribe({
      next: () => {
        this.info.set(message);
        this.rafraichir();
        this.rafraichirPlusTard();
        this.modifie.emit();
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Action impossible.'),
    });
  }

  classeStatut(d: DemandePieceDto): string {
    switch (d.statut) {
      case 'RECU':
      case 'RAPPROCHEE':
        return 'badge-recu';
      case 'ANNULEE':
      case 'EXPIREE':
        return 'badge-annulee';
      case 'ECHEC_ENVOI':
        return 'badge-erreur';
      default:
        return 'badge-attente';
    }
  }

  /** Une demande reçue ou close n'a plus à afficher ses incidents d'envoi. */
  close(d: DemandePieceDto): boolean {
    return d.statut === 'RECU' || d.statut === 'RAPPROCHEE' || d.statut === 'ANNULEE' || d.statut === 'EXPIREE';
  }

  libelleMotif(motif: string): string {
    switch (motif) {
      case 'NO_CONTACT':
        return 'Aucun contact joignable pour ce client';
      case 'INVALID_CONTACT':
        return 'Message refusé par le fournisseur (numéro invalide ou canal indisponible)';
      case 'SEND_FAILED':
        return 'Envoi impossible après plusieurs tentatives';
      default:
        return motif;
    }
  }

  libelleMessage(statut: string): string {
    switch (statut) {
      case 'PENDING':
        return 'en boîte d’envoi';
      case 'SENDING':
        return 'envoi en cours';
      case 'SENT':
        return 'accepté par le fournisseur';
      case 'FAILED':
        return 'échec';
      case 'CANCELLED':
        return 'annulé';
      default:
        return statut;
    }
  }
}
