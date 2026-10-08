import { APIRequestContext } from '@playwright/test';

/** Amorçage par l'API (jeux de données de test jetables) partagé par le parcours E2E et les captures visuelles. */

export const API = 'http://localhost:4201/api';

export interface Dossier {
  email: string;
  motDePasse: string;
  entrepriseId: string;
  exerciceId: string;
  entete: { Authorization: string };
  ecritureCapitalId: string;
}

async function ok<T>(r: { ok(): boolean; json(): Promise<T>; status(): number; text(): Promise<string> }): Promise<T> {
  if (!r.ok()) {
    throw new Error(`API ${r.status()} : ${await r.text()}`);
  }
  return r.json();
}

/** Un cabinet jetable, une entreprise, trois écritures (capital non qualifié, transports, achat). */
export async function amorcer(api: APIRequestContext): Promise<Dossier> {
  const suffixe = Math.random().toString(16).slice(2, 8);
  const email = `e2e-${suffixe}@test.ci`;
  const motDePasse = `Pw-${Math.random().toString(16).slice(2, 10)}-Test1!`;
  await ok(await api.post(`${API}/auth/register-cabinet`, { data: { raisonSocialeCabinet: `Cabinet E2E ${suffixe}`, pays: 'CI', email, motDePasse, nom: 'Expert', prenom: 'E2E', telephone: null } }));
  const login = await ok<{ token: string }>(await api.post(`${API}/auth/login`, { data: { email, motDePasse } }));
  const entete = { Authorization: `Bearer ${login.token}` };
  const entreprise = await ok<{ id: string }>(
    await api.post(`${API}/entreprises`, { headers: entete, data: { raisonSociale: `Société Démo ${suffixe}`, numeroContribuable: null, rccm: null, formeJuridique: null, regimeFiscal: null, secteurActivite: null, pays: 'CI', devise: 'XOF' } }),
  );
  const base = `${API}/entreprises/${entreprise.id}`;
  const journaux = await ok<{ id: string }[]>(await api.get(`${base}/journaux`, { headers: entete }));
  const comptes = await ok<{ id: string; numero: string }[]>(await api.get(`${base}/comptes`, { headers: entete }));
  const parNumero = new Map(comptes.map((c) => [c.numero, c.id]));
  const compte = async (numero: string, intitule: string): Promise<string> => {
    if (!parNumero.has(numero)) {
      const c = await ok<{ id: string }>(await api.post(`${base}/comptes`, { headers: entete, data: { numero, intitule, lettrable: false } }));
      parNumero.set(numero, c.id);
    }
    return parNumero.get(numero)!;
  };
  const aujourdhui = new Date().toISOString().slice(0, 10);
  const ecriture = async (libelle: string, lignes: [string, string, number, number][]): Promise<string> => {
    const corps = {
      journalId: journaux[0].id, dateEcriture: aujourdhui, libelle, numeroPiece: null,
      lignes: await Promise.all(lignes.map(async ([n, i, debit, credit]) => ({ compteId: await compte(n, i), libelle, debit, credit, tiersId: null }))),
    };
    const e = await ok<{ id: string }>(await api.post(`${base}/ecritures`, { headers: entete, data: corps }));
    await ok(await api.post(`${base}/ecritures/${e.id}/valider`, { headers: entete, data: {} }));
    return e.id;
  };
  const capital = await ecriture('Capital en numeraire', [['521', 'Banque', 1_000_000, 0], ['101', 'Capital social', 0, 1_000_000]]);
  await ecriture('Transports', [['612', 'Transports sur achats', 100_000, 0], ['521', 'Banque', 0, 100_000]]);
  await ecriture('Achat marchandises', [['601', 'Achats de marchandises', 300_000, 0], ['401', 'Fournisseurs', 0, 300_000]]);
  const exercices = await ok<{ id: string; statut: string }[]>(await api.get(`${base}/exercices`, { headers: entete }));
  return { email, motDePasse, entrepriseId: entreprise.id, exerciceId: exercices[0].id, entete, ecritureCapitalId: capital };
}

interface NoteApi {
  noteCode: string;
  version: number;
  applicabilityStatus: string;
  manualSections: { sectionCode: string; required: boolean; fields: { fieldCode: string; required: boolean; type: string }[] }[];
}

/** Ouvre la synthèse du dossier financier : c'est elle qui publie (ou ferme) les constats des états financiers que lit ensuite le Cabinet Workspace. */
export async function publierControles(api: APIRequestContext, d: Dossier): Promise<void> {
  // les mêmes appels que l'écran du dossier financier : chaque lecture d'un état synchronise ses constats avec les Findings
  for (const chemin of ['dossier-financier', 'compte-resultat', 'bilan', 'tft', 'notes']) {
    await ok(await api.get(`${API}/entreprises/${d.entrepriseId}/etats-financiers-syscohada/${chemin}?exerciceId=${d.exerciceId}`, { headers: d.entete }));
  }
}

/** Prépare un dossier jusqu'à « prêt pour revue » par l'API : flux qualifié, notes 2 / 17 / 22 saisies, toutes les notes décidées ; ni revue, ni clôture, ni finalisation. */
export async function preparerParApi(api: APIRequestContext, d: Dossier): Promise<void> {
  const base = `${API}/entreprises/${d.entrepriseId}/etats-financiers-syscohada`;
  const exo = `exerciceId=${d.exerciceId}`;
  const note = async (code: string): Promise<NoteApi> => ok(await api.get(`${base}/notes/${encodeURIComponent(code)}?${exo}`, { headers: d.entete }));
  await ok(await api.put(`${base}/ecritures/${d.ecritureCapitalId}/cash-flow-event`, { headers: d.entete, data: { event: 'CAPITAL_CASH_INCREASE' } }));

  const remplir = async (code: string): Promise<void> => {
    const n = await note(code);
    const fields = n.manualSections.flatMap((s) => s.fields.filter((f) => f.required).map((f) => ({ sectionCode: s.sectionCode, fieldCode: f.fieldCode, value: f.type === 'INTEGER' ? '40' : 'Renseigné pour le dossier de démonstration' })));
    await ok(await api.patch(`${base}/notes/${encodeURIComponent(code)}/manual-data?${exo}`, { headers: d.entete, data: { expectedVersion: n.version, fields, manualComment: null } }));
  };
  const n2 = await note('2');
  await ok(await api.patch(`${base}/notes/2/applicability?${exo}`, { headers: d.entete, data: { expectedVersion: n2.version, status: 'APPLICABLE', reason: 'Entité soumise au référentiel' } }));
  for (const code of ['2', '17', '22']) {
    await remplir(code);
  }
  await ok(await api.post(`${base}/notes/validate-candidates?${exo}`, { headers: d.entete, data: {} }));
  const espace = await ok<{ notes: NoteApi[] }>(await api.get(`${base}/notes?${exo}`, { headers: d.entete }));
  for (const n of espace.notes.filter((x) => x.applicabilityStatus === 'TO_DETERMINE')) {
    await ok(await api.patch(`${base}/notes/${encodeURIComponent(n.noteCode)}/applicability?${exo}`, { headers: d.entete, data: { expectedVersion: n.version, status: 'NOT_APPLICABLE', reason: 'Sans objet pour cette entité' } }));
  }
  await publierControles(api, d);
}

/** Mène un dossier jusqu'au snapshot FINAL par l'API : préparation, clôture, finalisation. */
export async function finaliserParApi(api: APIRequestContext, d: Dossier): Promise<void> {
  await preparerParApi(api, d);
  const base = `${API}/entreprises/${d.entrepriseId}/etats-financiers-syscohada`;
  await ok(await api.post(`${API}/entreprises/${d.entrepriseId}/exercices/${d.exerciceId}/cloturer`, { headers: d.entete, data: { avertissementsConfirmes: true, motif: 'Dettes fournisseurs ouvertes acceptées' } }));
  await ok(await api.post(`${base}/DOSSIER_COMPLET/finaliser?exerciceId=${d.exerciceId}`, { headers: d.entete, data: {} }));
}

export interface Cabinet extends Dossier {
  collaborateur: { id: string; email: string; motDePasse: string };
}

/** L exercice courant d une entreprise (ouvert le plus ancien) : celui sur lequel portent les constats et les demandes du cabinet. */
export async function exerciceCourant(api: APIRequestContext, d: Dossier): Promise<string> {
  const exercices = await ok<{ id: string; dateFin: string; statut: string }[]>(await api.get(`${API}/entreprises/${d.entrepriseId}/exercices`, { headers: d.entete }));
  const ouverts = exercices.filter((e) => e.statut === 'OUVERT').sort((a, b) => a.dateFin.localeCompare(b.dateFin));
  return (ouverts[0] ?? [...exercices].sort((a, b) => b.dateFin.localeCompare(a.dateFin))[0]).id;
}

/** Un dossier amorcé + un collaborateur du cabinet (créé par l'API réelle) auquel le dossier est affecté ; l'expert est le propriétaire du cabinet. */
export async function amorcerCabinet(api: APIRequestContext, executerControles = true): Promise<Cabinet> {
  const d = await amorcer(api);
  const motDePasse = `Pw-${Math.random().toString(16).slice(2, 10)}-Collab1!`;
  const email = `collab-${Math.random().toString(16).slice(2, 8)}@test.ci`;
  const membre = (await ok<{ membre: { id: string } }>(await api.post(`${API}/cabinet/membres`, { headers: d.entete, data: { email, nom: 'Kone', prenom: 'Fatou', role: 'COLLABORATEUR', motDePasse } }))).membre;
  const exerciceId = await exerciceCourant(api, d);
  const login = await ok<{ token: string }>(await api.post(`${API}/auth/login`, { data: { email: d.email, motDePasse: d.motDePasse } }));
  const expertId = JSON.parse(Buffer.from(login.token.split('.')[1], 'base64url').toString()).sub ?? null;
  await ok(await api.patch(`${API}/cabinet/dossiers/${d.entrepriseId}/${exerciceId}/affectation`, { headers: d.entete, data: { collaborateurId: membre.id, expertId: null } }));
  if (executerControles) {
    await ok(await api.post(`${API}/entreprises/${d.entrepriseId}/revue/executer`, { headers: d.entete, data: {} }));
  }
  void expertId;
  return { ...d, exerciceId, collaborateur: { id: membre.id, email, motDePasse } };
}
