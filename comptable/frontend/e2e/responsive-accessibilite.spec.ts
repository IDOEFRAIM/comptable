import { Page, expect, request, test } from '@playwright/test';
import { amorcerCabinet } from './support';

/**
 * Contrôles minimaux de pilote sur les trois résolutions de bureau courantes : aucune page principale ne déborde horizontalement, et les bases d'accessibilité sont présentes
 * (langue du document, titre de page, un seul h1, champs de formulaire étiquetés, boutons nommés, navigation au clavier jusqu'au bouton de connexion).
 */

const RESOLUTIONS = [
  { largeur: 1366, hauteur: 768 },
  { largeur: 1440, hauteur: 900 },
  { largeur: 1920, hauteur: 1080 },
];

async function sansDebordement(page: Page, nom: string): Promise<void> {
  const { defilement, fenetre } = await page.evaluate(() => ({ defilement: document.documentElement.scrollWidth, fenetre: window.innerWidth }));
  expect(defilement, `${nom} : défilement horizontal de la page (${defilement}px pour une fenêtre de ${fenetre}px)`).toBeLessThanOrEqual(fenetre + 1);
}

async function baseAccessibilite(page: Page, nom: string): Promise<void> {
  await expect(page.locator('html')).toHaveAttribute('lang', /fr/);
  expect((await page.title()).trim().length, `${nom} : titre de page`).toBeGreaterThan(0);
  expect(await page.locator('h1').count(), `${nom} : exactement un titre de niveau 1`).toBe(1);
  const sansNom = await page.evaluate(() => {
    const nomAccessible = (el: Element): string => (el.getAttribute('aria-label') ?? el.getAttribute('title') ?? (el as HTMLElement).innerText ?? '').trim();
    return Array.from(document.querySelectorAll('button')).filter((b) => !nomAccessible(b) && !b.querySelector('[aria-label]')).length;
  });
  expect(sansNom, `${nom} : boutons sans nom accessible`).toBe(0);
  const champsSansEtiquette = await page.evaluate(() =>
    Array.from(document.querySelectorAll('input:not([type=hidden]):not([type=checkbox]):not([type=radio]), select, textarea')).filter((el) => {
      const i = el as HTMLInputElement;
      return !(i.labels && i.labels.length) && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby') && !el.closest('label') && !el.getAttribute('placeholder');
    }).length,
  );
  expect(champsSansEtiquette, `${nom} : champs sans étiquette`).toBe(0);
}

for (const r of RESOLUTIONS) {
  test(`${r.largeur}×${r.hauteur} : connexion, tableau de bord du cabinet, dossier financier sans débordement et accessibles`, async ({ page }) => {
    await page.setViewportSize({ width: r.largeur, height: r.hauteur });
    const api = await request.newContext();
    const c = await amorcerCabinet(api, false);

    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
    await sansDebordement(page, 'connexion');
    await baseAccessibilite(page, 'connexion');

    // navigation au clavier : e-mail -> mot de passe -> bouton -> validation par la touche Entrée
    await page.getByRole('textbox', { name: /email/i }).focus();
    await page.keyboard.type(c.email);
    await page.keyboard.press('Tab');
    await page.keyboard.type(c.motDePasse);
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/cabinet$/);
    await expect(page.getByRole('heading', { name: 'Cabinet' })).toBeVisible();
    await sansDebordement(page, 'tableau de bord du cabinet');
    await baseAccessibilite(page, 'tableau de bord du cabinet');

    await page.goto(`/entreprises/${c.entrepriseId}/etats-financiers`);
    await expect(page.getByRole('heading', { name: /Dossier financier/ })).toBeVisible();
    await sansDebordement(page, 'dossier financier');
    await baseAccessibilite(page, 'dossier financier');

    await page.goto('/cabinet/equipe');
    await expect(page.getByRole('heading', { name: /Équipe/ })).toBeVisible();
    await sansDebordement(page, 'équipe');
    await baseAccessibilite(page, 'équipe');
  });
}
