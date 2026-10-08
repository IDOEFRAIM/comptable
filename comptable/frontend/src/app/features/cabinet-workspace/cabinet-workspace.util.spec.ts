import { TestBed } from '@angular/core/testing';
import { StatusComponent } from '../../shared/ui/status.component';
import { dateCourte, depuisJours, ilYA, routeDossier } from './cabinet-workspace.util';

describe('cabinet-workspace.util', () => {
  const maintenant = new Date('2026-10-05T12:00:00Z');

  it('ilYA : lecture rapide de la dernière activité', () => {
    expect(ilYA(null, maintenant)).toBe('—');
    expect(ilYA('2026-10-05T11:59:30Z', maintenant)).toBe('à l’instant');
    expect(ilYA('2026-10-05T11:15:00Z', maintenant)).toBe('il y a 45 min');
    expect(ilYA('2026-10-05T10:00:00Z', maintenant)).toBe('il y a 2 h');
    expect(ilYA('2026-10-04T10:00:00Z', maintenant)).toBe('hier');
    expect(ilYA('2026-10-01T12:00:00Z', maintenant)).toBe('il y a 4 jours');
  });

  it('depuisJours et dateCourte', () => {
    expect(depuisJours(0)).toBe('Aujourd’hui');
    expect(depuisJours(1)).toBe('Depuis 1 jour');
    expect(depuisJours(5)).toBe('Depuis 5 jours');
    expect(dateCourte('2026-10-05')).toBe('05/10/2026');
  });

  it('routeDossier : on ouvre là où l\'action suivante se fait', () => {
    expect(routeDossier('PRET_POUR_REVUE', 'e')).toEqual(['/entreprises', 'e', 'etats-financiers']);
    expect(routeDossier('EN_ATTENTE_CLIENT', 'e')).toEqual(['/entreprises', 'e', 'demandes-pieces']);
    expect(routeDossier('A_DEMARRER', 'e')).toEqual(['/entreprises', 'e', 'tableau-de-bord']);
    expect(routeDossier('ATTENTION_REQUISE', 'e')).toEqual(['/entreprises', 'e', 'revision']);
  });
});

describe('StatusComponent : statuts opérationnels du cabinet', () => {
  const statuts = ['A_DEMARRER', 'EN_COURS', 'ATTENTION_REQUISE', 'EN_ATTENTE_CLIENT', 'PRET_POUR_REVUE', 'EN_REVUE', 'PRET_A_CLOTURER', 'CLOTURE', 'FINALISE'];

  it('chaque statut a une icône ET un libellé (jamais la couleur seule), le libellé du backend prime', () => {
    for (const s of statuts) {
      const f = TestBed.createComponent(StatusComponent);
      f.componentRef.setInput('statut', s);
      f.detectChanges();
      const html = f.nativeElement as HTMLElement;
      expect(html.querySelector('svg')).withContext(s).not.toBeNull();
      expect(html.innerText.trim().length).withContext(s).toBeGreaterThan(2);
    }
    const f = TestBed.createComponent(StatusComponent);
    f.componentRef.setInput('statut', 'EN_ATTENTE_CLIENT');
    f.componentRef.setInput('texte', 'En attente du client');
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).innerText).toContain('En attente du client');
  });
});
