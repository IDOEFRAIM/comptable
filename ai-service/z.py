from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

def generer_facture_juridique(nom_fichier):
    c = canvas.Canvas(nom_fichier, pagesize=A4)
    width, height = A4

    # En-tête Émetteur
    c.setFont("Helvetica-Bold", 14)
    c.drawString(50, height - 50, "Cabinet Juridique LEXDIGITAL")
    c.setFont("Helvetica", 10)
    c.drawString(50, height - 65, "Expertise Droit du Numérique")
    
    # Informations Client
    c.setFont("Helvetica-Bold", 10)
    c.drawString(400, height - 50, "Facturé à :")
    c.setFont("Helvetica", 10)
    c.drawString(400, height - 65, "TechInnov Faso SAS")
    c.drawString(400, height - 80, "Ouaga 2000, Ouagadougou")

    # Infos facture
    c.setFont("Helvetica-Bold", 12)
    c.drawString(50, height - 150, "FACTURE : LEX-202608-001")
    c.setFont("Helvetica", 10)
    c.drawString(50, height - 165, "Date : 15/08/2026")

    # Liste des services
    y = height - 220
    c.setFont("Helvetica-Bold", 10)
    c.drawString(50, y, "Description des services")
    c.drawString(450, y, "Montant (FCFA)")
    
    c.setFont("Helvetica", 10)
    c.drawString(50, y-20, "Rédaction contrats de travail")
    c.drawString(450, y-20, "150 000")
    
    c.drawString(50, y-40, "Audit conformité RGPD")
    c.drawString(450, y-40, "250 000")
    
    c.drawString(50, y-60, "Conseil levée de fonds")
    c.drawString(450, y-60, "200 000")

    # Ligne de séparation
    c.line(50, y-80, 550, y-80)

    # Totaux
    c.setFont("Helvetica-Bold", 12)
    c.drawString(350, y-110, "Total HT : 600 000 FCFA")
    c.drawString(350, y-130, "TVA (18%) : 108 000 FCFA")
    c.setFont("Helvetica-Bold", 14)
    c.drawString(350, y-160, "TOTAL À PAYER : 708 000 FCFA")

    c.save()
    print(f"Facture juridique générée : {nom_fichier}")

if __name__ == "__main__":
    generer_facture_juridique("facture_juridique_test.pdf")