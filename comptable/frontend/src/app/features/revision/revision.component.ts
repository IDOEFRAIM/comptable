import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { DemandeService } from '../demandes/demande.service';
import { finalize, map } from 'rxjs/operators';
import {
  CategorieControle,
  ConstatDto,
  DomaineControle,
  EspaceRevueDto,
  InvestigationDto,
  LIBELLES_CAUSE,
  LIBELLES_DOMAINE,
  ObjetLieDto,
  PreparationClotureDto,
  ROUTE_INVESTIGATION,
  LIBELLES_CATEGORIE,
  LIBELLES_OBJET,
  LIBELLES_SEVERITE,
  routePour,
  SeveriteControle,
  StatutConstat,
  SyntheseRevueDto,
  TuileRevue,
} from './models/revision.model';
import { RevisionService } from './revision.service';

/**
 * « Révision » : ce que le comptable doit traiter maintenant pour cette entreprise. Une ligne = une
 * décision possible (quoi, quel objet, pourquoi, quelles valeurs, quelle action) ; les tuiles
 * filtrent la liste, la relance des contrôles réaligne les constats sur l'état réel du dossier.
 */
@Component({
  selector: 'app-revision',
  standalone: true,
  imports: [FormsModule, DatePipe, DecimalPipe],
  templateUrl: './revision.component.html',
  styleUrl: './revision.component.scss',
})
export class RevisionComponent implements OnInit, OnDestroy {
  private readonly service = inject(RevisionService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly demandeService = inject(DemandeService);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesSeverite = LIBELLES_SEVERITE;
  readonly libellesCategorie = LIBELLES_CATEGORIE;
  readonly severites = Object.keys(LIBELLES_SEVERITE) as SeveriteControle[];
  readonly categories = Object.keys(LIBELLES_CATEGORIE) as CategorieControle[];
  readonly typesObjet = Object.keys(LIBELLES_OBJET);
  readonly libellesObjet = LIBELLES_OBJET;

  readonly synthese = signal<SyntheseRevueDto | null>(null);
  readonly espace = signal<EspaceRevueDto | null>(null);
  readonly preparation = signal<PreparationClotureDto | null>(null);
  readonly investigation = signal<InvestigationDto | null>(null);
  readonly investigationDe = signal<string | null>(null);
  readonly investigationEnCours = signal(false);
  readonly libellesDomaine = LIBELLES_DOMAINE;
  readonly libellesCause = LIBELLES_CAUSE;
  readonly constats = signal<ConstatDto[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly chargement = signal(false);
  readonly relance = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly info = signal<string | null>(null);
  readonly detailOuvert = signal<string | null>(null);
  readonly ecartEnCours = signal<string | null>(null);
  motifEcart = '';

  readonly taille = 25;
  domaine: DomaineControle | '' = '';
  tuile: TuileRevue | '' = '';
  severite: SeveriteControle | '' = '';
  categorie: CategorieControle | '' = '';
  statut: StatutConstat | '' = '';
  entityType = '';
  du = '';
  au = '';
  recherche = '';
  private minuteur: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    const tuileUrl = this.route.snapshot.queryParamMap.get('tuile') as TuileRevue | null;
    if (tuileUrl) {
      this.tuile = tuileUrl;
    }
    this.rafraichir();
  }

  ngOnDestroy(): void {
    if (this.minuteur) {
      clearTimeout(this.minuteur);
    }
  }

  rafraichir(): void {
    this.chargerEspace();
    this.chargerSynthese();
    this.chargerConstats();
  }

  chargerEspace(): void {
    this.service.espace(this.entrepriseId()).subscribe({
      next: (e) => {
        this.espace.set(e);
        // En séquence : l'espace vient de rafraîchir les contrôles, la préparation à la clôture s'appuie sur cette exécution.
        this.service.preparationCloture(this.entrepriseId()).subscribe({
          next: (p) => this.preparation.set(p),
          error: () => this.preparation.set(null),
        });
      },
      error: () => this.erreur.set("Impossible de charger l'espace de révision."),
    });
  }

  choisirDomaine(code: DomaineControle): void {
    this.domaine = this.domaine === code ? '' : code;
    this.filtresChanges();
  }

  /** « Pourquoi ? » : reconstitue la chaîne source -> anomalie de ce constat (lecture seule, rien n'est corrigé). */
  pourquoi(constat: ConstatDto): void {
    if (this.investigationDe() === constat.id) {
      this.investigationDe.set(null);
      this.investigation.set(null);
      return;
    }
    this.investigationDe.set(constat.id);
    this.investigation.set(null);
    this.investigationEnCours.set(true);
    this.service
      .investigation(this.entrepriseId(), constat.id)
      .pipe(finalize(() => this.investigationEnCours.set(false)))
      .subscribe({
        next: (r) => this.investigation.set(r),
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible de reconstituer l'historique de ce constat."),
      });
  }

  /** Navigue vers l'écran où traiter un objet cité par l'investigation (libellé lisible, jamais d'identifiant). */
  ouvrirObjet(objet: ObjetLieDto): void {
    const page = ROUTE_INVESTIGATION[objet.type];
    if (page) {
      this.router.navigate(['/entreprises', this.entrepriseId(), page]);
    }
  }

  peutOuvrirObjet(objet: ObjetLieDto): boolean {
    return ROUTE_INVESTIGATION[objet.type] !== undefined;
  }

  libelleCause(code: string): string {
    return this.libellesCause[code] ?? code;
  }

  allerALaCloture(): void {
    this.router.navigate(['/entreprises', this.entrepriseId(), 'etats-financiers']);
  }

  chargerSynthese(): void {
    this.service.synthese(this.entrepriseId()).subscribe({
      next: (s) => this.synthese.set(s),
      error: () => this.erreur.set('Impossible de charger la synthèse de la revue.'),
    });
  }

  chargerConstats(): void {
    this.chargement.set(true);
    this.service
      .constats(this.entrepriseId(), {
        tuile: this.tuile,
        domaine: this.domaine,
        severite: this.severite,
        categorie: this.categorie,
        statut: this.statut,
        entityType: this.entityType,
        du: this.du,
        au: this.au,
        q: this.recherche,
        page: this.page(),
        taille: this.taille,
      })
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (p) => {
          this.constats.set(p.contenu);
          this.total.set(p.total);
          this.totalPages.set(p.totalPages);
        },
        error: () => this.erreur.set('Impossible de charger les constats.'),
      });
  }

  choisirTuile(code: TuileRevue): void {
    this.tuile = this.tuile === code ? '' : code;
    this.filtresChanges();
  }

  filtresChanges(): void {
    this.page.set(0);
    this.chargerConstats();
  }

  rechercheChangee(): void {
    if (this.minuteur) {
      clearTimeout(this.minuteur);
    }
    this.minuteur = setTimeout(() => this.filtresChanges(), 300);
  }

  reinitialiser(): void {
    this.domaine = '';
    this.tuile = '';
    this.severite = '';
    this.categorie = '';
    this.statut = '';
    this.entityType = '';
    this.du = '';
    this.au = '';
    this.recherche = '';
    this.filtresChanges();
  }

  changerPage(delta: number): void {
    const cible = this.page() + delta;
    if (cible < 0 || cible >= this.totalPages()) {
      return;
    }
    this.page.set(cible);
    this.chargerConstats();
  }

  relancer(): void {
    this.relance.set(true);
    this.erreur.set(null);
    this.service
      .relancer(this.entrepriseId())
      .pipe(finalize(() => this.relance.set(false)))
      .subscribe({
        next: (r) => {
          this.info.set(
            `Contrôles relancés : ${r.crees} nouveau(x), ${r.resolus} résolu(s) automatiquement, ${r.misAJour} mis à jour.`,
          );
          this.rafraichir();
        },
        error: () => this.erreur.set('Impossible de relancer les contrôles.'),
      });
  }

  basculerDetail(id: string): void {
    this.detailOuvert.set(this.detailOuvert() === id ? null : id);
  }

  ouvrir(constat: ConstatDto): void {
    const page = routePour(constat);
    if (page) {
      this.router.navigate(['/entreprises', this.entrepriseId(), page]);
    }
  }

  peutOuvrir(constat: ConstatDto): boolean {
    return routePour(constat) !== null;
  }

  /** Une opération sans justificatif : on peut le demander au client (WhatsApp / e-mail selon le canal du dossier). */
  peutDemanderPiece(constat: ConstatDto): boolean {
    return constat.code === 'MISSING_DOCUMENT' && constat.entityType === 'ECRITURE_COMPTABLE' && constat.status === 'OPEN';
  }

  demanderPiece(constat: ConstatDto): void {
    this.demandeService.demanderPourConstat(this.entrepriseId(), constat.id, {}).subscribe({
      next: () => {
        this.info.set('Demande de pièce envoyée : le constat se fermera tout seul à la réception de la pièce.');
        this.rafraichir();
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer la demande de pièce.'),
    });
  }

  demanderEcart(id: string): void {
    this.ecartEnCours.set(id);
    this.motifEcart = '';
  }

  annulerEcart(): void {
    this.ecartEnCours.set(null);
  }

  confirmerEcart(constat: ConstatDto): void {
    if (!this.motifEcart.trim()) {
      return;
    }
    this.service.ecarter(this.entrepriseId(), constat.id, this.motifEcart.trim()).subscribe({
      next: () => {
        this.ecartEnCours.set(null);
        this.info.set('Constat écarté : il reste consultable et tracé dans l’audit.');
        this.rafraichir();
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible d’écarter ce constat.'),
    });
  }

  libelleObjet(type: string): string {
    return this.libellesObjet[type] ?? type;
  }

  valeurs(constat: ConstatDto): [string, string][] {
    return Object.entries(constat.evidence ?? {})
      .filter(([, v]) => v !== null && v !== undefined && typeof v !== 'object')
      .map(([k, v]) => [k, String(v)] as [string, string]);
  }

  classeSeverite(s: SeveriteControle): string {
    return 'sev-' + s.toLowerCase();
  }
}
