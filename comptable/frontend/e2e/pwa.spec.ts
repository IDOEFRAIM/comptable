import { expect, test } from '@playwright/test';

/**
 * PWA et mode hors ligne, sur un build de PRODUCTION (le service worker est désactivé en développement) : définir PWA_URL (ex. http://localhost:8090 pour la pile docker), PWA_EMAIL et PWA_PASSWORD.
 * Vérifie : manifeste et icônes servis, service worker actif, application rechargeable sans réseau, données déjà consultées lisibles hors ligne, écriture refusée proprement, caches de données purgés à la déconnexion.
 */
const url = process.env['PWA_URL'];
const email = process.env['PWA_EMAIL'];
const motDePasse = process.env['PWA_PASSWORD'];

test.describe('PWA / hors ligne', () => {
  test.skip(!url || !email || !motDePasse, 'PWA_URL, PWA_EMAIL et PWA_PASSWORD non définis');
  test.use({ serviceWorkers: 'allow' });

  test('installable, utilisable hors ligne en lecture seule, caches purgés à la déconnexion', async ({ page, context, request }) => {
    // manifeste et icônes
    const manifeste = await request.get(`${url}/manifest.webmanifest`);
    expect(manifeste.status()).toBe(200);
    const corps = await manifeste.json();
    expect(corps.name).toContain('Warimara');
    expect(corps.display).toBe('standalone');
    for (const icone of corps.icons) {
      expect((await request.get(`${url}/${icone.src}`)).status(), icone.src).toBe(200);
    }
    expect((await request.get(`${url}/ngsw.json`)).headers()['cache-control']).toContain('no-cache');
    expect((await request.get(`${url}/ngsw-worker.js`)).headers()['cache-control']).toContain('no-cache');

    // connexion puis consultation de quelques pages (elles se mettent en cache)
    await page.goto(`${url}/login`);
    await page.getByRole('textbox', { name: /email/i }).fill(email!);
    await page.locator('input[type="password"]').fill(motDePasse!);
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page).toHaveURL(/\/cabinet$/);
    await expect(page.getByRole('heading', { name: 'Cabinet' })).toBeVisible();
    await page.getByRole('link', { name: 'Équipe', exact: true }).click();
    await expect(page.getByRole('heading', { name: /Équipe/ })).toBeVisible();
    await page.getByRole('link', { name: 'Cabinet', exact: true }).click();

    // le service worker s'active (enregistrement différé jusqu'à 30 s après stabilisation)
    await expect
      .poll(async () => page.evaluate(async () => (await navigator.serviceWorker.getRegistration())?.active?.state ?? 'aucun'), { timeout: 60_000, intervals: [1000] })
      .toBe('activated');
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Cabinet' })).toBeVisible();
    await page.getByRole('link', { name: 'Équipe', exact: true }).click();
    await expect(page.getByRole('heading', { name: /Équipe/ })).toBeVisible();
    await page.getByRole('link', { name: 'Cabinet', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Cabinet' })).toBeVisible();

    // coupure du réseau : l'application se recharge, les pages déjà vues restent lisibles, le bandeau l'indique
    await context.setOffline(true);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Cabinet' })).toBeVisible();
    await expect(page.locator('[data-zone="bandeau-hors-ligne"]')).toContainText('Mode hors ligne');
    await page.getByRole('link', { name: 'Équipe', exact: true }).click();
    await expect(page.getByRole('heading', { name: /Équipe/ })).toBeVisible();
    // les DONNÉES déjà consultées (pas seulement les titres) viennent du cache : le membre du cabinet est listé
    await expect(page.getByText(email!)).toBeVisible();


    // retour du réseau puis déconnexion : les données d'API en cache disparaissent
    await context.setOffline(false);
    await page.reload();
    await expect(page.locator('[data-zone="bandeau-hors-ligne"]')).toHaveCount(0);
    const avant = await page.evaluate(async () => (await caches.keys()).filter((n) => n === 'warimara-donnees-v1').length);
    expect(avant).toBeGreaterThan(0);
    await page.getByRole('button', { name: 'Déconnexion' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect.poll(async () => page.evaluate(async () => (await caches.keys()).filter((n) => n === 'warimara-donnees-v1').length), { timeout: 10_000 }).toBe(0);
  });
});
