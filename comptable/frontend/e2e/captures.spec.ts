import { Browser, BrowserContext, Page, expect, request, test } from '@playwright/test';
import { amorcer, finaliserParApi } from './support';
import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * Captures d'écran du dossier financier (revue visuelle, avant / après redesign). Désactivé par défaut : `CAPTURE_DIR=docs/screenshots/financial-review/apres npx playwright test captures`.
 * Deux dossiers jetables : A (avec anomalies : vue d'ensemble, états, notes, contrôles) et B (finalisé par l'API : version finale / snapshot).
 */
const dossierCaptures = process.env['CAPTURE_DIR'];
const VIEWPORTS = [
  { nom: '1440x900', width: 1440, height: 900 },
  { nom: '1920x1080', width: 1920, height: 1080 },
];

async function ouvrirSession(browser: Browser, email: string, motDePasse: string, viewport: { width: number; height: number }): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  page.on('dialog', (d) => d.accept());
  await page.goto('/login');
  await page.getByRole('textbox', { name: /email/i }).fill(email);
  await page.locator('input[type="password"]').fill(motDePasse);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/(entreprises|cabinet)/);
  return { context, page };
}

test('captures du dossier financier', async ({ browser }) => {
  test.skip(!dossierCaptures, 'CAPTURE_DIR non défini');
  test.setTimeout(600_000);
  const racine = path.resolve(dossierCaptures!);
  const api = await request.newContext();
  const a = await amorcer(api);
  const b = await amorcer(api);
  await finaliserParApi(api, b);

  for (const vp of VIEWPORTS) {
    const dossier = path.join(racine, vp.nom);
    fs.mkdirSync(dossier, { recursive: true });
    const viewport = { width: vp.width, height: vp.height };

    // ---- dossier A : anomalies
    const sA = await ouvrirSession(browser, a.email, a.motDePasse, viewport);
    await sA.page.goto(`/entreprises/${a.entrepriseId}/etats-financiers`);
    await expect(sA.page.locator('[data-champ="finalisable"]')).toBeVisible({ timeout: 60_000 });
    await sA.page.screenshot({ path: path.join(dossier, '1-overview.png'), fullPage: true });
    for (const [onglet, fichier] of [['COMPTE_RESULTAT', '2-compte-resultat'], ['BILAN', '3-bilan'], ['TFT', '4-tft']] as const) {
      await sA.page.locator(`[data-onglet="${onglet}"]`).click();
      await expect(sA.page.locator('[data-etat]').first()).toBeVisible();
      await sA.page.screenshot({ path: path.join(dossier, `${fichier}.png`), fullPage: true });
    }
    await sA.page.locator('[data-onglet="NOTES"]').click();
    await sA.page.locator('ul.notes li[data-note="17"]').click();
    await expect(sA.page.locator('.detail-note')).toContainText('17');
    await sA.page.screenshot({ path: path.join(dossier, '5-notes.png'), fullPage: true });
    await sA.page.locator('[data-onglet="CONTROLES"]').click();
    await expect(sA.page.locator('[data-zone="anomalies"]')).toBeVisible();
    await sA.page.screenshot({ path: path.join(dossier, '6-controles.png'), fullPage: true });
    await sA.page.locator('[data-onglet="VERSION_FINALE"]').click();
    await expect(sA.page.locator('[data-zone="pre-finalisation"]')).toBeVisible();
    await sA.page.screenshot({ path: path.join(dossier, '7-pre-finalisation.png'), fullPage: true });
    await sA.context.close();

    // ---- dossier B : finalisé
    const sB = await ouvrirSession(browser, b.email, b.motDePasse, viewport);
    await sB.page.goto(`/entreprises/${b.entrepriseId}/etats-financiers`);
    await expect(sB.page.locator('[data-onglet="VERSION_FINALE"]')).toBeVisible({ timeout: 60_000 });
    await sB.page.locator('select#exercice-dossier').selectOption({ index: 1 }).catch(() => undefined);
    await sB.page.locator('[data-onglet="VERSION_FINALE"]').click();
    await expect(sB.page.locator('[data-zone="snapshot-viewer"]')).toBeVisible({ timeout: 60_000 });
    await sB.page.screenshot({ path: path.join(dossier, '8-snapshot.png'), fullPage: true });
    await sB.context.close();
  }
});
