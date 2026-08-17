export interface AlerteFraudeDto {
  code: string;
  motif: string;
  gravite: string;
  actionRecommandee: string | null;
}

export interface DocumentEnAttenteValidationDto {
  id: string;
  entrepriseId: string;
  typeDocument: string;
  canalSource: string;
  nomFichierOriginal: string | null;
  contentType: string | null;
  urlFichier: string;
  montantHt: number | null;
  montantTva: number | null;
  montantTtc: number | null;
  datePiece: string | null;
  confiance: number;
  /** 'HAUTE' (>= 95%, badge vert) ou 'A_VERIFIER' (< 95%, badge orange + messageExtraction). */
  niveauConfiance: 'HAUTE' | 'A_VERIFIER';
  /** Motif deja produit par le moteur d'extraction (VLM ou heuristique) au moment de l'ingestion :
   * explique precisement pourquoi la confiance est ce qu'elle est (Sprint 8). */
  messageExtraction: string | null;
  /** 'FAIBLE' | 'MOYEN' | 'CRITIQUE', ou null si l'agent de fraude n'a rien a signaler. */
  scoreRisqueFraude: string | null;
  alertesFraude: AlerteFraudeDto[];
  createdAt: string;
}

export interface ValiderDocumentRequest {
  montantHt: number | null;
  montantTva: number | null;
  montantTtc: number | null;
  datePiece: string | null;
}
