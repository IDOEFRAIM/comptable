import { Page, expect, request, test } from '@playwright/test';
import { API, amorcerCabinet, preparerParApi } from './support';

/**
 * Parcours « pilote » : ce que vivent réellement un cabinet et ses collaborateurs au quotidien, sans développeur derrière l'écran.
 * Cabinet, entreprise et écritures sont amorcés par l'API réelle (comptes jetables) ; la connexion, le mot de passe temporaire, la réinitialisation, la revue et le dossier financier passent par l'interface.
 */

async function seConnecter(page: Page, email: string, motDePasse: string): Promise<void> {
  await page.goto('/login');
  await page.getByRole('textbox', { name: /email/i }).fill(email);
  await page.locator('input[type="password"]').fill(motDePasse);
  await page.getByRole('button', { name: 'Se connecter' }).click();
}

async function ok<T>(r: { ok(): boolean; json(): Promise<T>; status(): number; text(): Promise<string> }): Promise<T> {
  if (!r.ok()) {
    throw new Error(`API ${r.status()} : ${await r.text()}`);
  }
  return r.json();
}

test('mot de passe temporaire : changement obligatoire avant tout travail, puis accès à l’espace du cabinet', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);
  const email = `nouveau-${Math.random().toString(16).slice(2, 8)}@test.ci`;
  // sans mot de passe fourni, le serveur en génère un temporaire, montré une seule fois à l'expert
  const cree = await ok<{ motDePasseTemporaire: string }>(await api.post(`${API}/cabinet/membres`, { headers: c.entete, data: { email, nom: 'Traore', prenom: 'Issa', role: 'COLLABORATEUR', motDePasse: null } }));
  const temporaire = cree.motDePasseTemporaire;
  expect(temporaire).toBeTruthy();

  await seConnecter(page, email, temporaire);
  await expect(page).toHaveURL(/\/changer-mot-de-passe$/);
  await expect(page.locator('[data-champ="motif-obligatoire"]')).toContainText('temporaire');

  // aucune page de travail n'est accessible tant que le mot de passe n'est pas changé
  await page.goto('/cabinet');
  await expect(page).toHaveURL(/\/changer-mot-de-passe$/);

  const definitif = `Def-${Math.random().toString(16).slice(2, 10)}-Yw2!`;
  await page.locator('[data-champ="actuel"]').fill(temporaire);
  await page.locator('[data-champ="nouveau"]').fill(definitif);
  await page.locator('[data-champ="confirmation"]').fill(definitif);
  await page.locator('[data-action="changer-mot-de-passe"]').click();
  await expect(page).toHaveURL(/\/cabinet$/);
  await expect(page.getByRole('heading', { name: 'Cabinet' })).toBeVisible();

  // l'ancien mot de passe temporaire ne fonctionne plus
  const refus = await api.post(`${API}/auth/login`, { data: { email, motDePasse: temporaire } });
  expect(refus.status()).toBe(401);
});

test('réinitialisation : lien à usage unique émis par l’expert, ancien mot de passe remplacé, lien inutilisable ensuite', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);
  const emis = await ok<{ jeton: string; chemin: string }>(await api.post(`${API}/cabinet/equipe/${c.collaborateur.id}/reinitialiser-mot-de-passe`, { headers: c.entete, data: {} }));
  expect(emis.chemin).toContain('/reinitialiser-mot-de-passe');

  const nouveau = `Rst-${Math.random().toString(16).slice(2, 10)}-Kp3!`;
  await page.goto(emis.chemin);
  await page.locator('[data-champ="nouveau"]').fill(nouveau);
  await page.locator('[data-champ="confirmation"]').fill(nouveau);
  await page.locator('[data-action="reinitialiser"]').click();
  await expect(page.locator('[data-champ="reinitialisation-reussie"]')).toContainText('Mot de passe modifié');

  await seConnecter(page, c.collaborateur.email, nouveau);
  await expect(page).toHaveURL(/\/cabinet$/);

  // le lien est à usage unique ; l'ancien mot de passe est refusé ; la réponse ne révèle rien sur l'existence d'un compte
  await page.context().clearCookies();
  const rejeu = await api.post(`${API}/auth/reset-password`, { data: { jeton: emis.jeton, nouveauMotDePasse: `Autre-${nouveau}` } });
  expect(rejeu.status()).toBeGreaterThanOrEqual(400);
  expect((await api.post(`${API}/auth/login`, { data: { email: c.collaborateur.email, motDePasse: c.collaborateur.motDePasse } })).status()).toBe(401);
});

test('dossier de travail : le collaborateur ouvre le dossier financier de l’entreprise sans erreur technique visible', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);
  await preparerParApi(api, c);
  await seConnecter(page, c.collaborateur.email, c.collaborateur.motDePasse);
  await expect(page).toHaveURL(/\/cabinet$/);
  await page.goto(`/entreprises/${c.entrepriseId}/etats-financiers`);
  await expect(page.getByRole('heading', { name: /Dossier financier/ })).toBeVisible();
  const corps = await page.locator('body').innerText();
  expect(corps).not.toMatch(/Exception|stacktrace|SQL|org\.springframework/i);
});

test('isolation : un expert d’un autre cabinet ne voit pas l’entreprise (404) et la diagnostique de support reste réservée à l’expert', async () => {
  const api = await request.newContext();
  const a = await amorcerCabinet(api, false);
  const b = await amorcerCabinet(api, false);
  const autre = await api.get(`${API}/entreprises/${a.entrepriseId}`, { headers: b.entete });
  expect(autre.status()).toBe(404);
  const jetonCollab = (await ok<{ token: string }>(await api.post(`${API}/auth/login`, { data: { email: a.collaborateur.email, motDePasse: a.collaborateur.motDePasse } }))).token;
  const interdit = await api.get(`${API}/cabinet/support/diagnostic`, { headers: { Authorization: `Bearer ${jetonCollab}` } });
  expect(interdit.status()).toBe(403);
  const expert = await api.get(`${API}/cabinet/support/diagnostic`, { headers: a.entete });
  expect(expert.status()).toBe(200);
});
