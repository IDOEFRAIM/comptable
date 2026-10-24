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


# Une piece petite (bordereau de banque, ticket photographie de loin, capture recadree) donne
# peu de pixels au modele de vision, qui la declare alors "illisible". On l'agrandit avant envoi.
TAILLE_MIN_COTE_LONG = 1600
TAILLE_MAX_COTE_LONG = 2400


def preparer_image_pour_vlm(contenu: bytes) -> tuple[bytes, str]:
    """Redresse l'orientation EXIF, agrandit (LANCZOS) les images dont le grand cote est sous
    TAILLE_MIN_COTE_LONG, reduit celles qui depassent TAILLE_MAX_COTE_LONG, normalise le contraste.
    Retourne (png_bytes, 'image/png'). En cas d'echec de decodage, rend l'image d'origine."""
    from PIL import Image, ImageOps

    try:
        image = Image.open(io.BytesIO(contenu))
        image = ImageOps.exif_transpose(image).convert("RGB")
        grand_cote = max(image.size)
        if grand_cote < TAILLE_MIN_COTE_LONG:
            ratio = TAILLE_MIN_COTE_LONG / grand_cote
        elif grand_cote > TAILLE_MAX_COTE_LONG:
            ratio = TAILLE_MAX_COTE_LONG / grand_cote
        else:
            ratio = 1.0
        if ratio != 1.0:
            image = image.resize(
                (round(image.width * ratio), round(image.height * ratio)), Image.Resampling.LANCZOS
            )
        image = ImageOps.autocontrast(image, cutoff=1)
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        return buffer.getvalue(), "image/png"
    except Exception:
        return contenu, "image/png"
