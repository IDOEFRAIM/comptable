import { expect, request, test } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { API, amorcerCabinet } from './support';

/** Captures du Cabinet Workspace (revue visuelle). Désactivé par défaut : `CAPTURE_DIR=../docs/screenshots/cabinet-workspace npx playwright test captures-cabinet`. */
const dossierCaptures = process.env['CAPTURE_DIR'];

test('captures du Cabinet Workspace', async ({ browser }) => {
  test.skip(!dossierCaptures, 'CAPTURE_DIR non défini');
  test.setTimeout(600_000);
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);
  const noms = ['Boulangerie Soleil', 'Transports Kouassi', 'Pharmacie Centrale', 'Cabinet Dentaire Mensah', 'Quincaillerie du Port'];
  const ids: string[] = [];
  for (const nom of noms) {
    const r = await api.post(`${API}/entreprises`, { headers: c.entete, data: { raisonSociale: nom, numeroContribuable: null, rccm: null, formeJuridique: null, regimeFiscal: null, secteurActivite: null, pays: 'CI', devise: 'XOF' } });
    ids.push((await r.json()).id);
  }
  await api.post(`${API}/entreprises/${ids[0]}/demandes-pieces`, { headers: c.entete, data: { libelle: 'Relevé bancaire de septembre', envoyer: false } });
  await api.post(`${API}/entreprises/${ids[1]}/demandes-pieces`, { headers: c.entete, data: { libelle: 'Facture carburant', envoyer: false } });
  const demain = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
  const hier = new Date(Date.now() - 3 * 86400000).toISOString().slice(0, 10);
  await api.post(`${API}/cabinet/echeances`, { headers: c.entete, data: { entrepriseId: c.entrepriseId, type: 'CLOTURE_ANNUELLE', libelle: 'Clôture annuelle 2026', date: demain } });
  await api.post(`${API}/cabinet/echeances`, { headers: c.entete, data: { entrepriseId: ids[0], type: 'RELANCE_CLIENT', libelle: 'Relancer le relevé bancaire', date: hier } });

  for (const vp of [{ nom: '1440x900', width: 1440, height: 900 }, { nom: '1920x1080', width: 1920, height: 1080 }]) {
    const dossier = path.join(path.resolve(dossierCaptures!), vp.nom);
    fs.mkdirSync(dossier, { recursive: true });
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
    const page = await context.newPage();
    await page.goto('/login');
    await page.getByRole('textbox', { name: /email/i }).fill(c.email);
    await page.locator('input[type="password"]').fill(c.motDePasse);
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page).toHaveURL(/\/cabinet$/);
    await expect(page.locator('[data-zone="a-traiter"]')).toBeVisible();
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(dossier, '1-dashboard-expert.png'), fullPage: true });
    await page.goto('/cabinet/dossiers');
    await expect(page.locator('[data-dossier]').first()).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '2-portefeuille.png'), fullPage: true });
    await page.locator('[data-filtre="EN_ATTENTE_CLIENT"]').click();
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(dossier, '3-attente-client.png'), fullPage: true });
    await page.goto('/cabinet/echeances');
    await expect(page.locator('[data-zone="echeances-page"]')).toBeVisible();
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(dossier, '4-echeances.png'), fullPage: true });
    await page.goto('/cabinet/equipe');
    await expect(page.locator('[data-membre]').first()).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '6-equipe.png'), fullPage: true });
    await page.locator('[data-action="ajouter-membre"]').click();
    await page.locator('[data-champ="membre-prenom"]').fill('Awa');
    await page.locator('[data-champ="membre-nom"]').fill('Traore');
    await page.locator('[data-champ="membre-email"]').fill(`awa-${vp.nom}@test.ci`);
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(dossier, '7-equipe-formulaire.png'), fullPage: true });
    await page.locator('[data-action="enregistrer-membre"]').click();
    await expect(page.locator('[data-champ="mot-de-passe-temporaire"]')).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '8-equipe-membre-ajoute.png'), fullPage: true });
    await page.goto('/cabinet/dossiers');
    await page.locator('[data-dossier] input[type="checkbox"]').first().check();
    await page.locator('[data-champ="masse-collaborateur"]').selectOption({ label: 'Traore Awa' });
    await page.screenshot({ path: path.join(dossier, '9-portefeuille-affectation.png'), fullPage: true });
    await page.locator('[data-action="affecter-masse"]').click();
    await expect(page.locator('[data-champ="info"]')).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '10-portefeuille-apres-affectation.png'), fullPage: true });
    await context.close();
    const c2 = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
    const p2 = await c2.newPage();
    await p2.goto('/login');
    await p2.getByRole('textbox', { name: /email/i }).fill(c.collaborateur.email);
    await p2.locator('input[type="password"]').fill(c.collaborateur.motDePasse);
    await p2.getByRole('button', { name: 'Se connecter' }).click();
    await expect(p2.locator('[data-zone="a-traiter"]')).toBeVisible();
    await p2.waitForTimeout(500);
    await p2.screenshot({ path: path.join(dossier, '5-dashboard-collaborateur.png'), fullPage: true });
    await c2.close();
  }
});
