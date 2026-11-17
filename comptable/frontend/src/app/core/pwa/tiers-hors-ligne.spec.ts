import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TokenStorageService } from '../auth/token-storage.service';
import { FileAttenteService } from './file-attente.service';
import { fileAttenteStore } from './file-attente.store';
import { horsLigneInterceptor } from './hors-ligne.interceptor';
import { HorsLigneService } from './hors-ligne.service';

/**
 * Créer un client ou un fournisseur sans Internet : il reçoit un identifiant provisoire, utilisable dans les saisies suivantes ; à l'envoi, le serveur crée le vrai tiers et son identifiant
 * remplace le provisoire dans les saisies en attente.
 */
describe('Tiers créés hors ligne', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let service: FileAttenteService;
  const enLigne = signal(false);
  const attendre = () => new Promise((resolve) => setTimeout(resolve, 150));
  const achat = (fournisseurId: string) => ({ natureAchat: 'MARCHANDISE', fournisseurId, date: '2026-11-16', libelle: 'Sacs de riz', montantHt: 45000, compteChargeId: 'c601' });

  beforeEach(async () => {
    enLigne.set(false);
    for (const e of await fileAttenteStore.lister()) {
      await fileAttenteStore.supprimer(e.id);
    }
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([horsLigneInterceptor])),
        provideHttpClientTesting(),
        { provide: HorsLigneService, useValue: { enLigne } },
        { provide: TokenStorageService, useValue: { getEmail: () => 'a@cabinet.test', getToken: () => 'jwt' } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    service = TestBed.inject(FileAttenteService);
    await service.charger();
  });

  it('hors ligne, la création répond tout de suite avec un tiers à identifiant provisoire, sans appel réseau', async () => {
    const tiers = await new Promise<{ id: string; nom: string; provisoire?: boolean }>((resolve) =>
      client.post<{ id: string; nom: string; provisoire?: boolean }>('/api/entreprises/e1/tiers', { type: 'FOURNISSEUR', nom: 'Grossiste Sawadogo' }).subscribe(resolve),
    );
    http.expectNone('/api/entreprises/e1/tiers');
    expect(tiers.provisoire).toBeTrue();
    expect(tiers.id.startsWith('prov-')).toBeTrue();
    expect(service.enAttente().length).toBe(1);
    expect(service.enAttente()[0].libelle).toBe('Nouveau fournisseur : Grossiste Sawadogo');
    expect(service.tiersProvisoires('e1', 'FOURNISSEUR').map((t) => t.nom)).toEqual(['Grossiste Sawadogo']);
    expect(service.tiersProvisoires('e1', 'CLIENT')).toEqual([]);
    expect(service.tiersProvisoires('e2')).toEqual([]);
  });

  it('au retour d’Internet : le tiers est créé d’abord, puis l’opération qui l’utilise part avec son VRAI identifiant', async () => {
    const tiers = await service.enfilerTiers('e1', { type: 'FOURNISSEUR', nom: 'Grossiste Sawadogo' });
    await service.enfilerOperation('e1', 'operations/achat/proposition', achat(tiers.id), 'Achat : Sacs de riz (45 000)');

    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    const creation = http.expectOne('/api/entreprises/e1/tiers');
    expect(creation.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    creation.flush({ id: 'tiers-reel-1', nom: 'Grossiste Sawadogo' }, { status: 201, statusText: 'Created' });
    await attendre();
    const operation = http.expectOne('/api/entreprises/e1/operations/achat/proposition');
    expect((operation.request.body as { fournisseurId: string }).fournisseurId).toBe('tiers-reel-1');
    operation.flush({ id: 'p1' });
    await fin;

    expect(service.elements().length).toBe(0);
    expect(await fileAttenteStore.lister()).toEqual([]);
  });

  it('si le serveur refuse le tiers, l’opération qui l’utilise n’est PAS envoyée : elle est refusée avec un message qui nomme le tiers', async () => {
    const tiers = await service.enfilerTiers('e1', { type: 'FOURNISSEUR', nom: 'Grossiste Sawadogo' });
    await service.enfilerOperation('e1', 'operations/achat/proposition', achat(tiers.id), 'Achat : Sacs de riz (45 000)');

    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    http.expectOne('/api/entreprises/e1/tiers').flush({ code: 'COMPTE_COLLECTIF', message: 'Compte collectif fournisseurs non utilisable.' }, { status: 422, statusText: 'Unprocessable' });
    await fin;

    http.expectNone('/api/entreprises/e1/operations/achat/proposition');
    expect(service.refuses().length).toBe(2);
    const operation = service.refuses().find((e) => e.type === 'OPERATION');
    expect(operation?.erreur).toContain('Grossiste Sawadogo');
    expect(service.tiersProvisoires('e1')).toEqual([]);
  });

  it('une coupure pendant l’envoi du tiers garde tout en attente : rien n’est perdu, l’identifiant provisoire reste valable', async () => {
    const tiers = await service.enfilerTiers('e1', { type: 'CLIENT', nom: 'Boutique Ouedraogo' });
    await service.enfilerOperation('e1', 'operations/vente/proposition', { clientId: tiers.id, libelle: 'Vente', montantHt: 1000 }, 'Vente : Vente (1 000)');

    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    http.expectOne('/api/entreprises/e1/tiers').error(new ProgressEvent('error'), { status: 0 });
    await fin;

    expect(service.enAttente().length).toBe(2);
    expect(JSON.stringify(service.enAttente()[1].requete)).toContain(tiers.id);
    expect(service.tiersProvisoires('e1', 'CLIENT').length).toBe(1);
  });

  it('en ligne, la création n’est pas interceptée', () => {
    enLigne.set(true);
    client.post('/api/entreprises/e1/tiers', { type: 'CLIENT', nom: 'X' }).subscribe();
    http.expectOne('/api/entreprises/e1/tiers').flush({ id: 't1' });
    let erreur: HttpErrorResponse | null = null;
    enLigne.set(false);
    client.put('/api/entreprises/e1/tiers/t1', {}).subscribe({ error: (e) => (erreur = e) });
    expect((erreur as HttpErrorResponse | null)?.status).toBe(0);
  });
});
