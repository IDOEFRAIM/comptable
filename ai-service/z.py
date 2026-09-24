from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer
from reportlab.lib.styles import getSampleStyleSheet

pdf_file = "facture_fournitures_scolaires.pdf"
doc = SimpleDocTemplate(pdf_file, pagesize=A4)

styles = getSampleStyleSheet()
elements = []

# En-tête
elements.append(Paragraph("LIBRAIRIE PAPETERIE SCOLAIRE", styles["Title"]))
elements.append(Paragraph("25 Avenue de l'Éducation", styles["Normal"]))
elements.append(Paragraph("69003 Lyon", styles["Normal"]))
elements.append(Paragraph("Tél : 04 78 12 34 56", styles["Normal"]))
elements.append(Spacer(1, 20))

# Facture
elements.append(Paragraph("FACTURE N° ECO-2025-0087", styles["Heading2"]))
elements.append(Paragraph("Date : 12/09/2025", styles["Normal"]))
elements.append(Paragraph("Client : École Primaire Victor Hugo", styles["Normal"]))
elements.append(Spacer(1, 15))

# Produits
data = [
    ["Référence", "Désignation", "Qté", "PU HT (€)", "Total HT (€)"],
    ["CAH001", "Cahier 96 pages", 120, "1.20", "144.00"],
    ["STY002", "Stylo bille bleu", 200, "0.45", "90.00"],
    ["CRA003", "Boîte de crayons couleur", 30, "4.50", "135.00"],
    ["COL004", "Tube de colle 40g", 50, "1.80", "90.00"],
    ["RAM005", "Ramette papier A4 500 feuilles", 20, "5.90", "118.00"],
    ["REG006", "Règle plastique 30 cm", 80, "0.95", "76.00"],
    ["FEU007", "Pochette feuilles dessin A3", 40, "3.25", "130.00"],
    ["MAR008", "Marqueur effaçable tableau blanc", 25, "2.80", "70.00"],
]

table = Table(data, colWidths=[70, 200, 50, 70, 80])

table.setStyle(TableStyle([
    ('BACKGROUND', (0, 0), (-1, 0), colors.lightgrey),
    ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
    ('GRID', (0, 0), (-1, -1), 1, colors.black),
    ('ALIGN', (2, 1), (-1, -1), 'CENTER'),
]))

elements.append(table)
elements.append(Spacer(1, 20))

# Totaux
total_ht = 853.00
tva = 170.60
total_ttc = 1023.60

totaux = [
    ["Total HT", f"{total_ht:.2f} €"],
    ["TVA 20%", f"{tva:.2f} €"],
    ["Total TTC", f"{total_ttc:.2f} €"]
]

table_totaux = Table(totaux, colWidths=[120, 100])

table_totaux.setStyle(TableStyle([
    ('GRID', (0, 0), (-1, -1), 1, colors.black),
    ('FONTNAME', (0, 2), (-1, 2), 'Helvetica-Bold'),
]))

elements.append(table_totaux)
elements.append(Spacer(1, 15))

elements.append(
    Paragraph(
        "Paiement à 30 jours. Merci de votre confiance.",
        styles["Italic"]
    )
)

doc.build(elements)

print(f"PDF généré : {pdf_file}")