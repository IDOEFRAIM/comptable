import io

import pypdfium2 as pdfium

# Miroir de VlmOcrExtractionService.DPI_RENDU_PDF (Java, avant migration) : suffisant pour la
# lisibilite d'une facture standard sans generer une image demesuree.
DPI_RENDU_PDF = 150


def rendre_premiere_page_en_png(pdf_bytes: bytes, dpi: int = DPI_RENDU_PDF) -> bytes:
    """Ne rend que la premiere page : suffisant pour l'immense majorite des factures/reçus (une
    page) ; un PDF multi-pages peut toujours etre saisi manuellement. Partage entre
    extraction_service.py et agent_orchestrator.py."""
    document = pdfium.PdfDocument(pdf_bytes)
    if len(document) == 0:
        raise ValueError("PDF sans aucune page")
    page = document[0]
    bitmap = page.render(scale=dpi / 72)
    image = bitmap.to_pil()
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()
