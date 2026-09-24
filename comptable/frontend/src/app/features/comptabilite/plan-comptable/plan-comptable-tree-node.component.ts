import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CompteComptableArbreDto } from '../models/plan-comptable.model';

/** Noeud recursif de l'arbre du plan comptable - un composant standalone doit s'importer
 * lui-meme pour pouvoir se referencer dans son propre template (pattern standard Angular). */
@Component({
  selector: 'app-plan-comptable-tree-node',
  standalone: true,
  imports: [PlanComptableTreeNodeComponent],
  template: `
    <li class="noeud">
      <button
        type="button"
        class="ligne-noeud"
        [class.selectionne]="noeud.id === compteSelectionneId"
        [class.inactif]="!noeud.actif"
        (click)="selectionner.emit(noeud.id)"
      >
        <span class="numero">{{ noeud.numero }}</span>
        <span class="intitule">{{ noeud.intitule }}</span>
        <span class="badge-origine" [class.personnalise]="!noeud.origineReference">
          {{ noeud.origineReference ? 'SYSCOHADA' : 'Personnalisé' }}
        </span>
        @if (!noeud.actif) {
          <span class="badge-inactif">Désactivé</span>
        }
      </button>
      @if (noeud.enfants.length > 0) {
        <ul class="enfants">
          @for (enfant of noeud.enfants; track enfant.id) {
            <app-plan-comptable-tree-node
              [noeud]="enfant"
              [compteSelectionneId]="compteSelectionneId"
              (selectionner)="selectionner.emit($event)"
            />
          }
        </ul>
      }
    </li>
  `,
  styleUrl: './plan-comptable-tree-node.component.scss',
})
export class PlanComptableTreeNodeComponent {
  @Input({ required: true }) noeud!: CompteComptableArbreDto;
  @Input() compteSelectionneId: string | null = null;
  @Output() selectionner = new EventEmitter<string>();
}
