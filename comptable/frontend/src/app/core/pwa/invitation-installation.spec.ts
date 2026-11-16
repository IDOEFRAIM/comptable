import { TestBed } from '@angular/core/testing';
import { HorsLigneService } from './hors-ligne.service';
import { InvitationInstallationComponent } from './invitation-installation.component';

describe('InvitationInstallationComponent', () => {
  const cle = 'warimara.invitation-installation';

  beforeEach(() => localStorage.removeItem(cle));
  afterEach(() => localStorage.removeItem(cle));

  function afficher() {
    const fixture = TestBed.createComponent(InvitationInstallationComponent);
    fixture.detectChanges();
    return fixture;
  }
  const zone = (f: ReturnType<typeof afficher>) => f.nativeElement.querySelector('[data-zone="invitation-installation"]');

  it('ne propose rien tant que le navigateur ne permet pas l’installation', () => {
    const f = afficher();
    if (!TestBed.inject(HorsLigneService).sousIos) {
      expect(zone(f)).toBeNull();
    }
  });

  it('propose un bouton « Installer » dès que le navigateur le permet, et le retire après « Plus tard »', () => {
    const f = afficher();
    window.dispatchEvent(new Event('beforeinstallprompt', { cancelable: true }));
    f.detectChanges();
    expect(zone(f)).not.toBeNull();
    expect(f.nativeElement.querySelector('[data-action="installer-application-invitation"]')).not.toBeNull();

    f.nativeElement.querySelector('[data-action="masquer-invitation"]').click();
    f.detectChanges();
    expect(zone(f)).toBeNull();
    expect(Number(localStorage.getItem(cle))).toBeGreaterThan(0);
  });

  it('ne revient pas pendant la semaine qui suit « Plus tard »', () => {
    localStorage.setItem(cle, String(Date.now() - 24 * 3600 * 1000));
    const f = afficher();
    window.dispatchEvent(new Event('beforeinstallprompt', { cancelable: true }));
    f.detectChanges();
    expect(zone(f)).toBeNull();
  });

  it('disparaît une fois l’application installée', () => {
    const f = afficher();
    window.dispatchEvent(new Event('beforeinstallprompt', { cancelable: true }));
    f.detectChanges();
    expect(zone(f)).not.toBeNull();
    window.dispatchEvent(new Event('appinstalled'));
    f.detectChanges();
    expect(zone(f)).toBeNull();
  });
});
