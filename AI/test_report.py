"""Quick test to verify report generation produces valid output"""
from report_generator import generate_html_report, generate_rich_report

# Mock data
scenarios = [
    {
        "scenario_name": "Recherche Amazon",
        "nomSenario": "Recherche Amazon",
        "status": "PASSED",
        "duration_ms": 3200,
        "steps": [
            {"step_text": "Given l'utilisateur se trouve sur la page \"https://www.amazon.fr/\"", "status": "PASSED", "duration_ms": 1200, "error_message": None},
            {"step_text": "When l'utilisateur saisit \"souris sans fil\" dans le champ de recherche (id: twotabsearchtextbox)", "status": "PASSED", "duration_ms": 800, "error_message": None},
            {"step_text": "Then l'élément (id: search) devrait être visible", "status": "PASSED", "duration_ms": 600, "error_message": None},
        ],
        "screenshots": [],
        "error_message": None
    },
    {
        "scenario_name": "Cookie Consent",
        "nomSenario": "Cookie Consent",
        "status": "FAILED",
        "duration_ms": 5400,
        "steps": [
            {"step_text": "Given l'utilisateur se trouve sur la page \"https://www.amazon.fr/\"", "status": "PASSED", "duration_ms": 1100, "error_message": None},
            {"step_text": "When l'utilisateur clique sur le bouton \"Accepter\" (id: sp-cc-accept)", "status": "FAILED", "duration_ms": 2300, "error_message": "Element not found: sp-cc-accept"},
            {"step_text": "Then l'élément (id: sp-cc-accept) ne devrait pas être visible", "status": "SKIPPED", "duration_ms": 0, "error_message": None},
        ],
        "screenshots": [],
        "error_message": "Step 2 failed: Element not found"
    },
    {
        "scenario_name": "Personnaliser la page",
        "nomSenario": "Personnaliser la page",
        "status": "PASSED",
        "duration_ms": 4100,
        "steps": [
            {"step_text": "Given l'utilisateur se trouve sur la page \"https://www.amazon.fr/\"", "status": "PASSED", "duration_ms": 1000, "error_message": None},
            {"step_text": "When l'utilisateur clique sur le lien \"Personnaliser\" (id: nav-link-personalize)", "status": "PASSED", "duration_ms": 1500, "error_message": None},
            {"step_text": "Then l'URL de la page devrait être \"https://www.amazon.fr/gp/personalize\"", "status": "PASSED", "duration_ms": 900, "error_message": None},
        ],
        "screenshots": [],
        "error_message": None
    },
]

metadata = {
    "project_name": "Amazon FR",
    "url": "https://www.amazon.fr/",
    "timestamp": "2026-04-22T15:30:00"
}

# Test HTML
html = generate_html_report(scenarios, metadata)
with open("test_report_output.html", "w", encoding="utf-8") as f:
    f.write(html)
print(f"HTML report: {len(html)} chars -> test_report_output.html")

# Test full report (HTML + PDF)
report = generate_rich_report(scenarios, metadata)
print(f"PDF base64 length: {len(report['pdf_base64'])} chars")
print(f"PDF generation mode: {report['pdf_generation_mode']}")
print(f"Summary: {report['summary']}")
print("OK: All reports generated successfully")
