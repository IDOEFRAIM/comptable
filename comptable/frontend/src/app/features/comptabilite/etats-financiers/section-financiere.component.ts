import { Component, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { SectionFinanciereDto } from '../models/comptabilite.model';

@Component({
  selector: 'app-section-financiere',
  standalone: true,
  imports: [DecimalPipe],
  template: `
    <div class="section-financiere">
      <div class="ligne-section ligne-titre">
        <span>{{ section().libelle }}</span>
        <span>{{ section().total | number: '1.0-0' }}</span>
      </div>
      @for (poste of section().postes; track poste.numero) {
        <div class="ligne-section ligne-poste">
          <span>{{ poste.numero }} — {{ poste.intitule }}</span>
          <span>{{ poste.montant | number: '1.0-0' }}</span>
        </div>
      }
      @if (section().postes.length === 0) {
        <div class="ligne-section ligne-vide">
          <span>Aucun mouvement</span>
        </div>
      }
    </div>
  `,
  styleUrl: './section-financiere.component.scss',
})
export class SectionFinanciereComponent {
  readonly section = input.required<SectionFinanciereDto>();
}
