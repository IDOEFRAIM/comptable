import { Route } from '@angular/router';
import { routes } from '../../app.routes';
import { PAGES_CLIENT } from '../../core/aide/guides-taches';
import { MENU_DOSSIER, libellePage, menuPour } from './navigation-dossier';

function routesDuDossier(): string[] {
  const shell = routes.find((r) => r.path === '' && r.children)!;
  const dossier = shell.children!.find((r: Route) => r.path === 'entreprises/:entrepriseId')!;
  return dossier.children!.map((r) => r.path ?? '').filter((p) => p !== '');
}

describe('Menu du dossier', () => {
  const entrees = MENU_DOSSIER.flatMap((g) => g.entrees);

  it('chaque entrée mène à une route existante, et chaque page du dossier figure au menu', () => {
    const chemins = routesDuDossier();
    for (const e of entrees) {
      expect(chemins).withContext(e.lien).toContain(e.lien);
    }
    const pagesPrincipales = chemins.filter((c) => !c.includes('/'));
    for (const c of pagesPrincipales) {
      expect(entrees.map((e) => e.lien)).withContext(c).toContain(c);
    }
  });

  it('aucune entrée en double', () => {
    const liens = entrees.map((e) => e.lien);
    expect(new Set(liens).size).toBe(liens.length);
  });

  it('un client ne voit que les pages qui lui sont ouvertes, sans groupe vide', () => {
    const menu = menuPour(false);
    const liens = menu.flatMap((g) => g.entrees.map((e) => e.lien));
    expect(liens.length).toBeGreaterThan(0);
    for (const l of liens) {
      expect(PAGES_CLIENT).withContext(l).toContain(l);
    }
    expect(menu.every((g) => g.entrees.length > 0)).toBeTrue();
  });

  it('le cabinet voit tout le menu', () => {
    expect(menuPour(true).flatMap((g) => g.entrees).length).toBe(entrees.length);
  });

  it('libellé de la page ouverte, pour le fil d’Ariane', () => {
    expect(libellePage('rapprochement')).toBe('Banque');
    expect(libellePage('inconnue')).toBeNull();
  });
});
