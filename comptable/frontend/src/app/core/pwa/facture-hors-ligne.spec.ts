import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TokenStorageService } from '../auth/token-storage.service';
import { FileAttenteService } from './file-attente.service';
import { fileAttenteStore } from './file-attente.store';
import { horsLigneInterceptor } from './hors-ligne.interceptor';
import { HorsLigneService } from './hors-ligne.service';

/**
 * Devis et factures créés sans Internet : gardés en BROUILLON sur l'appareil, numérotés par le serveur à l'envoi (jamais de numéro inventé sur l'appareil). Valider, envoyer, accepter et convertir restent en ligne.
 */
describe('Devis et factures créés hors ligne', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let service: FileAttenteService;
  const enLigne = signal(false);
  const attendre = () => new Promise((resolve) => setTimeout(resolve, 150));
  const requete = (tiersId: string) => ({
    tiersId,
    dateEmission: '2026-11-16',
    objet: 'Conseil',
    lignes: [
      { designation: 'Conseil', quantite: 2, prixUnitaire: 50000, tauxTva: 18 },
      { designation: 'Déplacement', quantite: 1, prixUnitaire: 10000, tauxTva: 0 },
    ],
  });

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

  it('hors ligne, la création répond tout de suite par un brouillon sans numéro, avec les montants recalculés, sans appel réseau', async () => {
    const doc = await service.enfilerFacture('e1', 'DEVIS', requete('client-1'), 'Boutique Ouedraogo');
    expect(doc.provisoire).toBeTrue();
    expect(doc.id.startsWith('prov-')).toBeTrue();
    expect(doc.statut).toBe('BROUILLON');
    expect(doc.numero).toContain('attribué');
    expect(doc.tiersNom).toBe('Boutique Ouedraogo');
    expect(doc.montantHt).toBe(110000);
    expect(doc.montantTva).toBe(18000);
    expect(doc.montantTtc).toBe(128000);
    expect(doc.lignes.length).toBe(2);
    expect(service.enAttente()[0].libelle).toBe(`Devis en brouillon : Boutique Ouedraogo (${(128000).toLocaleString('fr-FR')})`);
    http.expectNone('/api/entreprises/e1/factures');
  });

  it('la liste hors ligne ne montre que les documents du bon dossier et du bon type', async () => {
    await service.enfilerFacture('e1', 'DEVIS', requete('c1'), 'A');
    await service.enfilerFacture('e1', 'FACTURE', requete('c1'), 'B');
    await service.enfilerFacture('e2', 'DEVIS', requete('c1'), 'C');
    expect(service.facturesProvisoires('e1', 'DEVIS').map((f) => f.tiersNom)).toEqual(['A']);
    expect(service.facturesProvisoires('e1', 'FACTURE').map((f) => f.tiersNom)).toEqual(['B']);
  });

  it('via l’intercepteur : une création de devis hors ligne est mise en file (201) ; en ligne elle part normalement', async () => {
    const reponse = await new Promise<{ provisoire?: boolean }>((resolve) => client.post<{ provisoire?: boolean }>('/api/entreprises/e1/factures', requete('c1'), { params: { type: 'DEVIS' } }).subscribe(resolve));
    expect(reponse.provisoire).toBeTrue();
    expect(service.enAttente().length).toBe(1);
    http.expectNone('/api/entreprises/e1/factures');

    enLigne.set(true);
    client.post('/api/entreprises/e1/factures', requete('c1'), { params: { type: 'DEVIS' } }).subscribe();
    http.expectOne((r) => r.url === '/api/entreprises/e1/factures' && r.params.get('type') === 'DEVIS').flush({ id: 'd1' });
  });

  it('au retour d’Internet, le brouillon part avec son type, une clé d’idempotence, et le serveur le numérote', async () => {
    await service.enfilerFacture('e1', 'FACTURE', requete('c1'), 'Client');
    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    const envoi = http.expectOne((r) => r.url === '/api/entreprises/e1/factures');
    expect(envoi.request.params.get('type')).toBe('FACTURE');
    expect(envoi.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    envoi.flush({ id: 'f1', numero: 'FAC-2026-0007' }, { status: 201, statusText: 'Created' });
    await fin;
    expect(service.elements().length).toBe(0);
  });

  it('un devis fait pour un client créé hors ligne part avec le VRAI identifiant du client', async () => {
    const tiers = await service.enfilerTiers('e1', { type: 'CLIENT', nom: 'Boutique Ouedraogo' });
    await service.enfilerFacture('e1', 'DEVIS', requete(tiers.id), tiers.nom);

    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    http.expectOne('/api/entreprises/e1/tiers').flush({ id: 'client-reel-9' }, { status: 201, statusText: 'Created' });
    await attendre();
    const devis = http.expectOne((r) => r.url === '/api/entreprises/e1/factures');
    expect((devis.request.body as { tiersId: string }).tiersId).toBe('client-reel-9');
    devis.flush({ id: 'd1' }, { status: 201, statusText: 'Created' });
    await fin;
    expect(service.elements().length).toBe(0);
  });

  it('si le client créé hors ligne est refusé, le devis n’est pas envoyé et le refus nomme le client', async () => {
    const tiers = await service.enfilerTiers('e1', { type: 'CLIENT', nom: 'Boutique Ouedraogo' });
    await service.enfilerFacture('e1', 'DEVIS', requete(tiers.id), tiers.nom);

    enLigne.set(true);
    const fin = service.synchroniser();
    await attendre();
    http.expectOne('/api/entreprises/e1/tiers').flush({ message: 'Compte collectif clients non utilisable.' }, { status: 422, statusText: 'Unprocessable' });
    await fin;
    http.expectNone('/api/entreprises/e1/factures');
    expect(service.refuses().find((e) => e.type === 'FACTURE')?.erreur).toContain('Boutique Ouedraogo');
  });
});
