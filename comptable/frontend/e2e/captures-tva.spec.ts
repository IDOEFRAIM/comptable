import { expect, request, test } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { amorcerTva, jetonDe } from './support-tva';

/** Captures de l'espace TVA (revue visuelle). Désactivé par défaut : `CAPTURE_DIR=../docs/screenshots/vat-workspace npx playwright test captures-tva`. */
const dossierCaptures = process.env['CAPTURE_DIR'];

test('captures de l’espace TVA', async ({ browser }) => {
  test.skip(!dossierCaptures, 'CAPTURE_DIR non défini');
  test.setTimeout(600_000);
  const api = await request.newContext();
  const d = await amorcerTva(api);
  await d.ecrireTva('F1001', 'VENTE', 1000, 100);
  await d.ecrireTva('F1002', 'VENTE', 2500, 250);
  await d.ecrireTva('F1003', 'VENTE', 1000, 103);
  await d.ecrireTva('A2001', 'ACHAT', 400, 40);
  await d.ecrireTva('A2002', 'ACHAT', 1200, 120);
  const mixte = await d.ecrireTva('M3001', 'MIXTE', 1000, 0);
  void mixte;
  const jetonCollab = await jetonDe(api, d.collaborateur.email, d.collaborateur.motDePasse);
  await d.ouvrirPeriode(jetonCollab);

  for (const vp of [{ nom: '1440x900', width: 1440, height: 900 }, { nom: '1920x1080', width: 1920, height: 1080 }]) {
    const dossier = path.join(path.resolve(dossierCaptures!), vp.nom);
    fs.mkdirSync(dossier, { recursive: true });
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
    const page = await context.newPage();
    await page.goto('/login');
    await page.getByRole('textbox', { name: /email/i }).fill(d.collaborateur.email);
    await page.locator('input[type="password"]').fill(d.collaborateur.motDePasse);
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.locator('[data-zone="a-traiter"]')).toBeVisible();
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(dossier, '1-cabinet-file.png'), fullPage: true });
    await page.goto(`/entreprises/${d.entrepriseId}/fiscalite/tva`);
    await expect(page.locator('[data-champ="solde"]')).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '2-vue-ensemble.png'), fullPage: true });
    await page.locator('[data-onglet="VENTES"]').click();
    await expect(page.locator('[data-zone="ventes"] [data-ligne]').first()).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '3-ventes.png'), fullPage: true });
    await page.locator('[data-zone="ventes"] [data-action="traiter"]').last().click();
    await expect(page.locator('[data-zone="origine"]')).toBeVisible();
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(dossier, '4-ventes-traitement.png'), fullPage: true });
    await page.locator('[data-onglet="ACHATS"]').click();
    await expect(page.locator('[data-zone="achats"] [data-ligne]').first()).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '5-achats.png'), fullPage: true });
    await page.locator('[data-onglet="AJUSTEMENTS"]').click();
    await page.locator('[data-action="nouvel-ajustement"]').click();
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(dossier, '6-ajustements.png'), fullPage: true });
    await page.locator('[data-onglet="CONTROLES"]').click();
    await expect(page.locator('[data-anomalie]').first()).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '7-controles.png'), fullPage: true });
    await page.locator('[data-onglet="DECLARATION"]').click();
    await expect(page.locator('[data-ligne-declaration]').first()).toBeVisible();
    await page.screenshot({ path: path.join(dossier, '8-declaration.png'), fullPage: true });
    await context.close();
  }
});
