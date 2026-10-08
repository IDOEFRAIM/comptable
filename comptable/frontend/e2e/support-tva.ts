import { APIRequestContext } from '@playwright/test';
import { API, Cabinet, amorcerCabinet } from './support';

/** Jeton d'un utilisateur de test (login réel). */
export async function jetonDe(api: APIRequestContext, email: string, motDePasse: string): Promise<{ Authorization: string }> {
  const r = await api.post(`${API}/auth/login`, { data: { email, motDePasse } });
  if (!r.ok()) {
    throw new Error(`Connexion impossible : ${r.status()}`);
  }
  return { Authorization: `Bearer ${(await r.json()).token}` };
}

async function ok<T>(r: { ok(): boolean; json(): Promise<T>; status(): number; text(): Promise<string> }): Promise<T> {
  if (!r.ok()) {
    throw new Error(`API ${r.status()} : ${await r.text()}`);
  }
  return r.json();
}

export interface DossierTva extends Cabinet {
  ecrireTva(piece: string, type: 'VENTE' | 'ACHAT' | 'MIXTE', ht: number, tva: number): Promise<string>;
  ouvrirPeriode(avec: { Authorization: string }): Promise<string>;
  preparer(avec: { Authorization: string }, periodeId: string): Promise<void>;
  /** Modification concurrente par un autre utilisateur (API) : ajoute un ajustement avec la version courante ; la version de la période augmente. */
  ajusterViaApi(avec: { Authorization: string }, periodeId: string): Promise<void>;
}

/**
 * Un dossier prêt pour la TVA : comptes de TVA créés et configurés, cadre fiscal FICTIF vérifié (taux 10 et 5 inventés pour le test, propre au cabinet jetable : aucune valeur réglementaire n'est semée par
 * l'application) et des écritures validées par l'API réelle.
 */
export async function amorcerTva(api: APIRequestContext, options: { cadreVerifie?: boolean } = {}): Promise<DossierTva> {
  const c = await amorcerCabinet(api, false);
  const base = `${API}/entreprises/${c.entrepriseId}`;
  const comptes = new Map<string, string>();
  const compte = async (numero: string): Promise<string> => {
    if (!comptes.has(numero)) {
      const existants = await ok<{ id: string; numero: string }[]>(await api.get(`${base}/comptes`, { headers: c.entete }));
      const trouve = existants.find((x) => x.numero === numero);
      const id = trouve ? trouve.id : (await ok<{ id: string }>(await api.post(`${base}/comptes`, { headers: c.entete, data: { numero, intitule: `Compte ${numero}`, lettrable: false } }))).id;
      comptes.set(numero, id);
    }
    return comptes.get(numero)!;
  };
  await ok(await api.post(`${base}/parametres-comptables/mappings`, { headers: c.entete, data: { purpose: 'TVA_COLLECTEE', contextKey: null, compteId: await compte('443900'), priorite: null } }));
  await ok(await api.post(`${base}/parametres-comptables/mappings`, { headers: c.entete, data: { purpose: 'TVA_RECUPERABLE', contextKey: null, compteId: await compte('445900'), priorite: null } }));
  if (options.cadreVerifie !== false) {
    const version = `TEST_ONLY-E2E-${Math.random().toString(16).slice(2, 8)}`;
    const cadre = await ok<{ id: string }>(await api.post(`${API}/cabinet/fiscal/cadres`, {
      headers: c.entete,
      data: {
        jurisdiction: 'CI', taxType: 'TVA', version, effectiveFrom: '2000-01-01',
        rules: { rates: [{ code: 'T10', label: 'Taux fictif A', percent: 10 }, { code: 'T5', label: 'Taux fictif B', percent: 5 }], periodicity: 'MONTHLY', toleranceAmount: 1, rateMatchTolerancePoints: 0.5 },
        sourceReferences: ['Source fictive TEST_ONLY (E2E), aucune valeur reglementaire'],
      },
    }));
    await ok(await api.post(`${API}/cabinet/fiscal/cadres/${cadre.id}/verifier`, { headers: c.entete }));
  }
  const journaux = await ok<{ id: string }[]>(await api.get(`${base}/journaux`, { headers: c.entete }));
  const maintenant = new Date();
  const debut = new Date(maintenant.getFullYear(), maintenant.getMonth(), 1, 12).toISOString().slice(0, 10);
  const fin = new Date(maintenant.getFullYear(), maintenant.getMonth() + 1, 0, 12).toISOString().slice(0, 10);

  const ecrireTva = async (piece: string, type: 'VENTE' | 'ACHAT' | 'MIXTE', ht: number, tva: number): Promise<string> => {
    const lignes: [string, number, number][] =
      type === 'VENTE' ? [['521', ht + tva, 0], ['701900', 0, ht], ['443900', 0, tva]]
        : type === 'ACHAT' ? [['601900', ht, 0], ['445900', tva, 0], ['521', 0, ht + tva]]
          : [['521', 2550, 0], ['601900', 1000, 0], ['445900', 50, 0], ['701900', 0, 3450], ['443900', 0, 100], ['401', 0, 50]];
    const corps = {
      journalId: journaux[0].id, dateEcriture: maintenant.toISOString().slice(0, 10), libelle: `${type} ${piece}`, numeroPiece: piece,
      lignes: await Promise.all(lignes.map(async ([n, d, k]) => ({ compteId: await compte(n), libelle: `${type} ${piece}`, debit: d, credit: k, tiersId: null }))),
    };
    const e = await ok<{ id: string }>(await api.post(`${base}/ecritures`, { headers: c.entete, data: corps }));
    await ok(await api.post(`${base}/ecritures/${e.id}/valider`, { headers: c.entete, data: {} }));
    return e.id;
  };
  const ouvrirPeriode = async (avec: { Authorization: string }): Promise<string> => (await ok<{ id: string }>(await api.post(`${base}/fiscalite/tva/periodes`, { headers: avec, data: { periodStart: debut, periodEnd: fin } }))).id;
  const versionCourante = async (avec: { Authorization: string }, periodeId: string): Promise<number> =>
    (await ok<{ version: number }>(await api.get(`${base}/fiscalite/tva/periodes/${periodeId}`, { headers: avec }))).version;
  const preparer = async (avec: { Authorization: string }, periodeId: string): Promise<void> => {
    await ok(await api.post(`${base}/fiscalite/tva/periodes/${periodeId}/preparer`, { headers: avec, data: { expectedVersion: await versionCourante(avec, periodeId) } }));
  };
  const ajusterViaApi = async (avec: { Authorization: string }, periodeId: string): Promise<void> => {
    await ok(await api.post(`${base}/fiscalite/tva/periodes/${periodeId}/ajustements`, {
      headers: avec, data: { kind: 'MANUAL', effect: 'OUTPUT', amount: 1, reason: 'Ajustement posé par un autre utilisateur', evidence: null, ecritureId: null, expectedVersion: await versionCourante(avec, periodeId) },
    }));
  };
  return { ...c, ecrireTva, ouvrirPeriode, preparer, ajusterViaApi };
}
