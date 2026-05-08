import os
import re
import sys
import unicodedata
import joblib
import pandas as pd
import logging
from pathlib import Path
from typing import List, Dict, Any, Optional
from fastapi import FastAPI, HTTPException, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from huggingface_hub import InferenceClient
import json

# Import the FeatureExtractor for joblib to properly deserialize the pipeline
from prepare_Elements_Detected import FeatureExtractor, filter_noisy_elements
from gherkin_executor import GherkinExecutor, ScenarioResult
from report_generator import generate_rich_report
from html_analyzer import analyze_html

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# Setup logging to show execution steps
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

try:
    from dotenv import load_dotenv

    project_root_env = Path(__file__).resolve().parent.parent / ".env"
    ai_env = Path(__file__).resolve().parent / ".env"
    load_dotenv(project_root_env, override=False)
    load_dotenv(ai_env, override=False)
except Exception as dotenv_error:
    logger.warning("Could not load .env files: %s", dotenv_error)

app = FastAPI(title="PFE Intelligent Scenario Generator API")

# CORS middleware — allow Angular dev server and any other frontend origins
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:4200", "http://127.0.0.1:4200", "http://localhost:8081", "http://127.0.0.1:8081"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
import torch

# ... le reste des imports classiques ...

# 1. Charger Random Forest
MODEL_PATH = "element_role_model_v1.pkl"
try:
    element_model = joblib.load(MODEL_PATH)
    print(f"✅ Modèle ML chargé depuis {MODEL_PATH}")
except Exception as e:
    print(f"⚠️ Impossible de charger le modèle ML : {e}")
    element_model = None

# 2. Charger le CodeT5 local finetuné
CODET5_PATH = "./codet5_finetuned_selenium"
try:
    codet5_tokenizer = AutoTokenizer.from_pretrained(CODET5_PATH)
    codet5_model = AutoModelForSeq2SeqLM.from_pretrained(CODET5_PATH)
    print(f"✅ Modèle CodeT5 local chargé depuis {CODET5_PATH}")
except Exception as e:
    print(f"⚠️ CodeT5 local non trouvé, entraînez le modèle d'abord : {e}")
    codet5_model = None
    codet5_tokenizer = None

# Les classes Pydantic
class ElementData(BaseModel):
    tag: str
    type: Optional[str] = ""
    name: Optional[str] = ""
    id: Optional[str] = ""
    text: Optional[str] = ""
    href: Optional[str] = None
    placeholder: Optional[str] = ""
    role: Optional[str] = ""
    required: Optional[Any] = False
    aria_label: Optional[str] = Field(default="", alias="aria-label")
    data_test: Optional[str] = Field(default="", alias="data-test")
    data_testid: Optional[str] = Field(default="", alias="data-testid")
    class_name: Optional[str] = Field(default="", alias="class")

class AnalyzeAppRequest(BaseModel):
    url: str
    elements: List[ElementData]
    focusObjective: Optional[str] = ""
    focusOptionnel: Optional[str] = ""

class SeleniumGenerationRequest(BaseModel):
    url: str
    objective: str
    elements: List[Dict[str, Any]]

HF_TOKEN = os.getenv("HF_TOKEN")

# Fallback model list — tried in order until one is available
FALLBACK_MODELS = [
    "Qwen/Qwen2.5-Coder-32B-Instruct",
    "Qwen/Qwen2.5-72B-Instruct",
    "mistralai/Mixtral-8x7B-Instruct-v0.1",
    "HuggingFaceH4/zephyr-orpo-141b-A35b-v0.1",
    "meta-llama/Meta-Llama-3-70B-Instruct",
]

import time as _time

def hf_chat_completion(messages, max_tokens=3000, temperature=0.2, retries_per_model=2):
    """
    Try each fallback model in order with retries.
    Automatically switches to the next model when the current one
    returns 503 (Service Unavailable) or 502 (Bad Gateway).
    """
    if not HF_TOKEN:
        raise RuntimeError("HF_TOKEN environment variable is required for Hugging Face calls.")

    last_error = None
    for model_name in FALLBACK_MODELS:
        for attempt in range(retries_per_model):
            try:
                c = InferenceClient(model_name, token=HF_TOKEN)
                print(f"[HF] Trying model: {model_name} (attempt {attempt+1})")
                response = c.chat_completion(
                    messages=messages,
                    max_tokens=max_tokens,
                    temperature=temperature
                )
                print(f"[HF] ✓ Success with model: {model_name}")
                return response
            except Exception as e:
                err_str = str(e)
                last_error = e
                is_overloaded = any(code in err_str for code in ["503", "502", "429", "Overloaded", "unavailable", "Temporarily"])
                if is_overloaded:
                    wait = 2 ** (attempt + 1)
                    print(f"[HF] ⚠ Model {model_name} overloaded (attempt {attempt+1}), retrying in {wait}s...")
                    _time.sleep(wait)
                    continue
                else:
                    print(f"[HF] ✗ Model {model_name} error: {err_str[:120]}")
                    break  # Non-retryable error, try next model
    # All models failed
    raise RuntimeError(f"All HF models failed. Last error: {last_error}")

# Keep backward-compatible alias without embedding a token in source code.
client = InferenceClient(FALLBACK_MODELS[0], token=HF_TOKEN) if HF_TOKEN else None

MIN_USER_STORY_SCENARIOS = 8
MIN_DYNAMIC_SCENARIOS = 12
MAX_DYNAMIC_SCENARIOS = 18
FOCUSED_MIN_SCENARIOS = 1
FOCUSED_MAX_SCENARIOS = 4

class UserStoryRequest(BaseModel):
    user_story: str

# Phase 3: Test Execution Models
class ScenarioItem(BaseModel):
    nomSenario: str
    senario: str
    selected: bool = True

class GenerateTestScriptRequest(BaseModel):
    scenario_text: str
    framework: str  # Selenium, Cypress, Playwright, Pytest (API)
    language: str   # Python, Java, JavaScript, TypeScript

class ExecuteScenarioRequest(BaseModel):
    projectId: str
    url: str
    scenarios: List[ScenarioItem]

# System prompt is separated
system_prompt = """Tu es un expert QA silencieux et automatique. Ton unique rôle est de convertir la User Story fournie en scénarios de test Gherkin.

ATTENTION :
1. NE GÉNÈRE SURTOUT PAS DE SCÉNARIOS HORS SUJET (ex: pas de test de connexion si la story n'en parle pas) !
2. Concentre-toi STRICTEMENT et EXCLUSIVEMENT sur la fonctionnalité décrite dans la User story.
3. Génère au minimum 8 scénarios distincts si la User Story le permet: succès, validation obligatoire, données invalides, limites, erreurs métier, permissions, navigation/retour, non-régression.
4. Chaque scénario doit tester une intention différente. Interdiction de répéter le même test avec seulement un titre différent.
5. INCLURE DES LOCATEURS : Lorsque c'est possible, ajoute des identifiants `(id: ...)` ou `(name: ...)` dans les actions `When` pour aider l'automate (ex: "When l'utilisateur clique sur le bouton connexion (id: login)").
6. VALIDATION CONCRÈTE : Dans les étapes `Then`, utilise EXCLUSIVEMENT ces formats reconnus par l'automate :
   - Visibilité: `Then l'élément (id: identifiant) devrait être visible`
   - Invisibilité: `Then l'élément (id: identifiant) ne devrait pas être visible`
   - URL: `Then l'URL de la page devrait être "https://..."`
   - URL contient: `Then l'URL devrait contenir "mot-clé"` (ATTENTION: pour les recherches, utilise le terme recherché seul, pas le format param=terme. Ex: "Then l'URL devrait contenir \"Kindle\"" et NON "Then l'URL devrait contenir \"s=Kindle\"")
   - Texte visible: `Then le texte "mot" devrait être visible`
   NE JAMAIS utiliser des phrases abstraites comme "la section est affichée" ou "le résultat est correct".
7. ÉTAPE GIVEN : Utilise TOUJOURS ce format exact pour la précondition :
   `Given l'utilisateur se trouve sur la page "https://url-du-site.com"`
8. Tu dois IMPÉRATIVEMENT utiliser ce format de TEXTE EXACT pour chaque scénario (c'est crucial pour l'analyse syntaxique automatique) :

### SCENARIO: [Nom court du scénario]
DESCRIPTION: [Courte explication]
EXPECTED: [Le résultat final attendu]
GHERKIN:
Feature: ...
  Scenario: ...
    Given l'utilisateur se trouve sur la page "https://..."
    When ...
    Then ...

Ne rajoute aucun mot d'introduction comme "Voici les scénarios" ni de conclusion. Rends uniquement la répétition de ces blocs pour chaque test possible."""

@app.post("/api/generate-gherkin")
async def generate_gherkin_scenarios(request: UserStoryRequest):
    if not request.user_story or not request.user_story.strip():
        raise HTTPException(status_code=400, detail="La User Story ne peut pas être vide.")
    
    user_prompt = f"""Convertis la User Story suivante en scénarios de test Gherkin en suivant LE FORMAT EXACT imposé.

Objectif qualité:
- Génère au moins {MIN_USER_STORY_SCENARIOS} scénarios si la User Story contient assez de règles.
- Sépare les scénarios par type: nominal, validation, négatif, limites, droits/permissions, erreur métier, navigation, régression.
- Chaque scénario doit avoir des données de test spécifiques et un résultat attendu vérifiable.

User Story:
{request.user_story}"""
    
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt}
    ]
    
    try:
        response = hf_chat_completion(
            messages=messages,
            max_tokens=4500,
            temperature=0.25
        )
        
        generated_text = response.choices[0].message.content.strip()
        print(f"--- Réponse brute IA ---\n{generated_text}\n------------------------")
        
        scenarios = _parse_scenarios_from_generated_text(generated_text, selected_default=True, source="IA_USER_STORY")
        
        if not scenarios:
            raise ValueError("L'IA n'a pas respecté le format d'extraction Markdown ('### SCENARIO: ').")
            
        # IMPORTANT : Le backend Spring Boot attend un objet contenant un tableau sous la clé "scenarios"
        # Map<String, Object> result = mapper.readValue(...) -> result.get("scenarios")
        return {"scenarios": scenarios}
        
    except ValueError as ve:
        raise HTTPException(status_code=500, detail=str(ve))
    except Exception as e:
        error_msg = str(e)
        print(f"Erreur technique: {error_msg}")
        raise HTTPException(status_code=502, detail=f"Hugging Face API Error: {error_msg}")

@app.post("/api/generate-test-script")
async def generate_test_script(request: GenerateTestScriptRequest):
    if not request.scenario_text or not request.scenario_text.strip():
        raise HTTPException(status_code=400, detail="Le texte du scénario ne peut pas être vide.")

    framework = request.framework
    language = request.language
    scenario = request.scenario_text.strip()

    # Build technology-specific prompt instructions
    framework_instructions = {
        "Selenium": "Use Selenium WebDriver with explicit waits. Use By locators (id, css selector, xpath). Include driver setup and teardown.",
        "Cypress": "Use Cypress commands (cy.get, cy.visit, cy.type, cy.click). Use Mocha BDD syntax (describe/it). Include proper assertions (should).",
        "Playwright": "Use Playwright Page object model. Use page.locator, page.goto, page.fill, page.click. Include async/await pattern.",
        "Pytest (API)": "Use pytest with requests library. Use fixtures for API base URL. Include proper assertions and HTTP methods (GET, POST, PUT, DELETE)."
    }

    language_instructions = {
        "Python": "Write in Python. Use proper Python syntax, imports, and conventions (snake_case).",
        "Java": "Write in Java. Use proper Java syntax, imports, class structure, and conventions (camelCase). Include main method or JUnit annotations.",
        "JavaScript": "Write in JavaScript. Use ES6+ syntax (const/let, arrow functions). Use proper module imports.",
        "TypeScript": "Write in TypeScript. Include type annotations, interfaces where appropriate. Use proper ES6+ module imports."
    }

    fw_instruction = framework_instructions.get(framework, framework_instructions["Selenium"])
    lang_instruction = language_instructions.get(language, language_instructions["Python"])

    # Determine file extension based on framework + language
    file_extensions = {
        ("Selenium", "Python"): "test_scenario.py",
        ("Selenium", "Java"): "TestScenario.java",
        ("Selenium", "JavaScript"): "test.scenario.js",
        ("Selenium", "TypeScript"): "test.scenario.ts",
        ("Cypress", "JavaScript"): "scenario.cy.js",
        ("Cypress", "TypeScript"): "scenario.cy.ts",
        ("Playwright", "Python"): "test_scenario.py",
        ("Playwright", "JavaScript"): "test.scenario.spec.js",
        ("Playwright", "TypeScript"): "test.scenario.spec.ts",
        ("Pytest (API)", "Python"): "test_api.py",
    }
    file_name = file_extensions.get((framework, language), "test_script.py")

    prompt = f"""[INST] You are an expert QA engineer. Generate a complete, runnable automated test script based on the following test scenario.

FRAMEWORK: {framework}
LANGUAGE: {language}

INSTRUCTIONS:
- {fw_instruction}
- {lang_instruction}
- Generate ONLY the code, no explanations or markdown.
- Include all necessary imports and setup.
- Add comments for each test step.
- Make the code production-ready with proper error handling.

TEST SCENARIO:
{scenario}

Generate the complete {framework} test script in {language}: [/INST]"""

    try:
        print(f"[Qwen-Coder] Generating {framework} script in {language}...")

        system_msg = f"""Tu es un expert QA et ingénieur automatisation. Tu génères UNIQUEMENT du code exécutable, sans aucune explication ni texte en dehors du code.
{fw_instruction}
{lang_instruction}
Inclus tous les imports nécessaires et le setup. Ajoute des commentaires pour chaque étape du test. Le code doit être production-ready avec gestion d'erreurs."""

        user_msg = f"""Génère le script de test {framework} complet en {language} pour le scénario suivant :

{scenario}

Génère UNIQUEMENT le code, sans explication ni markdown."""

        messages = [
            {"role": "system", "content": system_msg},
            {"role": "user", "content": user_msg}
        ]

        response = hf_chat_completion(
            messages=messages,
            max_tokens=2048,
            temperature=0.2
        )

        generated_code = response.choices[0].message.content.strip()

        # Clean up any markdown code blocks if the model adds them
        generated_code = re.sub(r'^```[\w]*\n?', '', generated_code)
        generated_code = re.sub(r'\n?```$', '', generated_code)
        generated_code = generated_code.strip()

        print(f"[Qwen-Coder] Script generated successfully ({len(generated_code)} chars)")

        return {
            "framework": framework,
            "language": language,
            "file_name": file_name,
            "generated_code": generated_code
        }

    except Exception as e:
        error_msg = str(e)
        print(f"Erreur génération script: {error_msg}")
        raise HTTPException(status_code=502, detail=f"Script generation error: {error_msg}")

@app.post("/api/analyze-app")
async def analyze_app(request: AnalyzeAppRequest):
    if not element_model:
        raise HTTPException(status_code=500, detail="Le modèle ML n'est pas chargé côté Python.")
        
    if not request.elements:
        raise HTTPException(status_code=400, detail="Aucun élément fourni pour l'analyse.")
        
    try:
        # 1. Convertir la requête JSON Pydantic en dict pour filtrer
        raw_elements_dicts = [el.dict(by_alias=True) for el in request.elements]
        
        # FILTRAGE DU BRUIT depuis prepare_Elements_Detected.py
        elements_dicts = filter_noisy_elements(raw_elements_dicts)
            
        print(f"[PIPELINE] {len(raw_elements_dicts)} éléments initiaux -> {len(elements_dicts)} après filtrage bruit")
        
        # Si tous les éléments disparaissent
        if not elements_dicts:
            return {"scenarios": [], "elements_classified": {}}

        df_elements = pd.DataFrame(elements_dicts)
        df_elements.fillna('', inplace=True)
        
        # 2. Inférence de ML via le modèle pré-entraîné
        predictions = element_model.predict(df_elements)

        # Get confidence scores
        prediction_probabilities = element_model.predict_proba(df_elements)
        max_probs = prediction_probabilities.max(axis=1)

        # 3. Compter les types d'éléments trouvés
        prediction_counts = pd.Series(predictions).value_counts().to_dict()
        print(f"✓ Éléments classifiés : {prediction_counts}")

        focus_objective = _normalize_focus_objective(request.focusObjective or request.focusOptionnel)
        elements_for_generation = _filter_elements_by_focus(elements_dicts, focus_objective)

        # 4. DYNAMIQUE: Générer des scénarios directement avec l'IA basé sur les éléments détectés
        # Créer une description structurée des éléments pour l'IA
        elements_description = _build_elements_description(elements_for_generation, prediction_counts)

        print(f"📝 Génération de scénarios dynamiques avec IA Qwen...")
        scenarios = _generate_scenarios_with_ai(request.url, elements_description, elements_for_generation, focus_objective)

        if focus_objective:
            scenarios = _ensure_minimum_scenarios(
                request.url,
                elements_for_generation,
                scenarios,
                FOCUSED_MIN_SCENARIOS,
                focus_objective,
                FOCUSED_MAX_SCENARIOS
            )
        else:
            scenarios = _ensure_minimum_scenarios(
                request.url,
                elements_for_generation,
                scenarios,
                MIN_DYNAMIC_SCENARIOS,
                "",
                MAX_DYNAMIC_SCENARIOS
            )

        if scenarios:
            print(f"✓ {len(scenarios)} scénarios générés avec succès")
        else:
            print(f"⚠️ Génération IA échouée, utilisation de fallback")
            scenarios = _get_fallback_scenarios(request.url)
            
        # 5. Retourner les scenarios + métadonnées au backend Java
        python_script = "# Script Selenium généré par l'IA (à implémenter Phase 3)"

        return {
            "message": f"ML Analysis successful: {len(scenarios)} scénarios générés. Éléments: {list(prediction_counts.keys())}",
            "scenarios": scenarios,
            "elements_classified": prediction_counts,
            "focus_objective": focus_objective,
            "python_script": python_script
        }

    except Exception as e:
        print(f"Erreur d'analyse: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/generate-selenium")
async def generate_selenium_code(request: SeleniumGenerationRequest):
    if not codet5_model or not codet5_tokenizer:
        raise HTTPException(status_code=503, detail="Le modèle CodeT5 local n'est pas chargé.")

    if not request.objective:
        raise HTTPException(status_code=400, detail="L'objectif utilisateur est requis.")
        
    # Convertir la liste d'éléments pour le prompt CodeT5 local (format du dataset)
    # Ex: auth_login: field #username, field #password | login admin
    
    # 1. Grouper par classe Random Forest
    elements_summary = []
    for el in request.elements:
        tag = el.get('tag', '')
        id_val = f"#{el.get('id')}" if el.get('id') else ""
        class_str = f".{el.get('class')}" if el.get('class') else ""
        elements_summary.append(f"{tag}{id_val}{class_str}")

    prompt_text = f"Generate Selenium script for: {request.objective}: {', '.join(elements_summary)} | {request.objective}"

    try:
        # Génération du code Python via notre propre CodeT5 local (finetuné)
        inputs = codet5_tokenizer(prompt_text, return_tensors="pt", max_length=128, truncation=True)
        # Génération
        outputs = codet5_model.generate(
            inputs.input_ids,
            max_length=256,
            num_beams=4,
            early_stopping=True
        )
        
        generated_code = codet5_tokenizer.decode(outputs[0], skip_special_tokens=True)
        
        # Ajout du header complet
        final_script = f"# Script Selenium Généré par CodeT5 Local\nfrom selenium import webdriver\nfrom selenium.webdriver.common.by import By\nfrom selenium.webdriver.support.ui import WebDriverWait\nfrom selenium.webdriver.support import expected_conditions as EC\nimport time\n\ndriver = webdriver.Chrome()\ndriver.implicitly_wait(10)\ndriver.get('{request.url}')\ntime.sleep(3)\n\n{generated_code.strip()}"
        
        print(f"[CodeT5 Local] Script Selenium généré avec succès !")
        
        return {
            "objective": request.objective,
            "selenium_code": final_script
        }

    except Exception as e:
        print(f"Erreur génération CodeT5 Local: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

import base64
import subprocess
from datetime import datetime
from reportlab.lib.pagesizes import letter
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader

class RunScriptRequest(BaseModel):
    script_code: str
    project_id: Optional[str] = "unknown"

@app.post("/api/run-python-script")
async def run_python_script(request: RunScriptRequest):
    if not request.script_code:
        raise HTTPException(status_code=400, detail="Le code du script est vide.")

    # Nom des fichiers temporaires
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    script_filename = f"temp_run_{timestamp}.py"
    screenshot_filename = f"error_snap_{timestamp}.png"
    pdf_filename = f"report_{timestamp}.pdf"

    # Wrapper le code pour gérer l'exécution, le screenshot, et la fermeture forcée du driver
    # On assume que le script généré instancie 'driver = webdriver.Chrome()'
    wrapped_code = f"""
import sys
import traceback
import time

try:
{chr(10).join(['    ' + line for line in request.script_code.split(chr(10))])}
    print("SUCCESS: Le script s'est exécuté sans erreur.")
except Exception as e:
    print(f"FAILED: Erreur d'exécution: {{e}}")
    traceback.print_exc()
    try:
        # Essayer de capturer l'écran si le driver est encore actif
        driver.save_screenshot('{screenshot_filename}')
        print(f"Screenshot saved to {screenshot_filename}")
    except Exception as snap_ex:
        print(f"Impossible de prendre un screenshot: {{snap_ex}}")
finally:
    try:
        driver.quit()
    except:
        pass
"""

    with open(script_filename, "w", encoding="utf-8") as f:
        f.write(wrapped_code)

    try:
        # Exécution du script Python dans un sous process (Mode réel, donc ça ouvrira Chrome UI si pas headless)
        process = subprocess.run(
            ["python", script_filename],
            capture_output=True,
            text=True,
            timeout=120  # Max 2 minutes
        )
        
        stdout = process.stdout
        stderr = process.stderr
        status = "SUCCESS" if "SUCCESS: Le script s'est exécuté sans erreur." in stdout else "FAILED"
        
        # Générer le PDF
        c = canvas.Canvas(pdf_filename, pagesize=letter)
        c.setFont("Helvetica-Bold", 16)
        c.drawString(50, 750, f"Rapport d'Exécution Selenium - {status}")
        
        c.setFont("Helvetica", 10)
        c.drawString(50, 730, f"Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        c.drawString(50, 715, f"Projet ID: {request.project_id}")
        
        c.setFont("Courier", 8)
        y = 690
        
        # Ajout des logs stdout (limités aux 20 dernières lignes)
        logs = (stdout + "\n" + stderr).split("\n")
        for line in logs[-25:]: 
            if y < 100:  # Gérer les marges du bas
                break
            c.drawString(50, y, line[:100]) # 100 chars max par ligne pour éviter de déborder
            y -= 12

        # Ajouter l'image si on est en Failed et qu'elle existe
        if os.path.exists(screenshot_filename):
            try:
                y_img = y - 300
                if y_img < 50:
                     c.showPage() # Nouvelle page si pas de place
                     y_img = 400
                img = ImageReader(screenshot_filename)
                c.drawImage(img, 50, y_img, width=400, preserveAspectRatio=True)
            except Exception as e:
                c.drawString(50, y-20, f"Erreur insertion image: {e}")

        c.save()

        # Lire le PDF en base64 pour le renvoyer
        with open(pdf_filename, "rb") as pdf_file:
            pdf_base64 = base64.b64encode(pdf_file.read()).decode('utf-8')

        return {
            "status": status,
            "logs": stdout + "\n" + stderr,
            "pdf_base64": pdf_base64
        }
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Timeout: L'exécution du script a pris trop de temps.")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Erreur d'exécution: {str(e)}")
    finally:
        # Nettoyage des fichiers temporaires
        for file_to_remove in [script_filename, screenshot_filename, pdf_filename]:
            if os.path.exists(file_to_remove):
                try:
                    os.remove(file_to_remove)
                except:
                    pass

# ================== HELPER FUNCTIONS FOR DYNAMIC SCENARIO GENERATION ==================

def _build_elements_description(elements_dicts: List[Dict], predictions: Dict) -> str:
    """
    Build a structured description of detected elements for AI prompt
    """
    description = "Page Analysis:\n"
    description += f"- Total interactive elements: {len(elements_dicts)}\n"
    description += f"- Element types detected: {list(predictions.keys())}\n\n"

    description += "Elements by type:\n"
    for element_type, count in predictions.items():
        description += f"  * {element_type}: {count}\n"

    description += "\nElement details:\n"
    for idx, el in enumerate(elements_dicts[:40]):
        tag = el.get('tag', '')
        f_type = el.get('type', '')
        name = el.get('name', '')
        el_id = el.get('id', '')
        placeholder = el.get('placeholder', '')
        role = el.get('role', '')
        aria_label = el.get('aria-label', '')
        data_testid = el.get('data-testid', '')
        text = el.get('text', '')[:50]
        elem_str = f"  {idx+1}. {tag}"
        if f_type:
            elem_str += f" (type: {f_type})"
        if name:
            elem_str += f" (name: {name})"
        if el_id:
            elem_str += f" (id: {el_id})"
        if placeholder:
            elem_str += f" (placeholder: {placeholder[:40]})"
        if role:
            elem_str += f" (role: {role})"
        if aria_label:
            elem_str += f" (aria-label: {aria_label[:40]})"
        if data_testid:
            elem_str += f" (data-testid: {data_testid})"
        if text:
            elem_str += f" - \"{text}\""
        description += elem_str + "\n"

    if len(elements_dicts) > 40:
        description += f"  ... and {len(elements_dicts) - 40} more elements\n"

    return description


def _parse_scenarios_from_generated_text(generated_text: str, selected_default: bool = True, source: str = "IA") -> List[Dict]:
    """Parse the strict scenario block format returned by the LLM."""
    scenarios = []
    blocks = generated_text.split("### SCENARIO:")

    for block in blocks:
        if not block.strip():
            continue

        titre = block.split("\n")[0].strip()
        desc_match = re.search(r'DESCRIPTION:\s*(.*?)\nEXPECTED:', block, re.DOTALL | re.IGNORECASE)
        exp_match = re.search(r'EXPECTED:\s*(.*?)\nGHERKIN:', block, re.DOTALL | re.IGNORECASE)
        gherkin_match = re.search(r'GHERKIN:\s*(.*)', block, re.DOTALL | re.IGNORECASE)

        description = desc_match.group(1).strip() if desc_match else "Scénario généré par IA"
        expected = exp_match.group(1).strip() if exp_match else "Test fonctionnel"
        gherkin = gherkin_match.group(1).strip() if gherkin_match else block.strip()
        gherkin = _clean_gherkin_text(gherkin)

        if titre and gherkin:
            scenario_text = f"{titre} {description} {expected} {gherkin}"
            scenario = {
                "nomSenario": titre,
                "description": description,
                "resultatAttendu": expected,
                "type": _infer_scenario_type(scenario_text),
                "senario": gherkin,
                "selected": selected_default,
                "source": source
            }
            scenarios.append(_repair_scenario_logic(scenario))

    return _deduplicate_scenarios(scenarios)


def _repair_scenario_logic(scenario: Dict) -> Dict:
    """Fix common LLM contradictions before scenarios reach Selenium."""
    gherkin = str(scenario.get("senario", ""))
    if not gherkin:
        return scenario

    # RTL text is sometimes generated reversed by the LLM/PDF extraction examples.
    gherkin = re.sub(r'"\s*ابحرم\s*"', '"مرحبا"', gherkin)

    negative_url_tokens = re.findall(
        r"Then\s+l['’]?URL(?:\s+de\s+la\s+page)?\s+ne\s+(?:devrait|doit)\s+pas\s+contenir\s+\"([^\"]+)\"",
        gherkin,
        flags=re.IGNORECASE
    )
    for token in negative_url_tokens:
        token_lower = token.lower()

        def replace_contradictory_input(match):
            value = match.group(2)
            if token_lower not in value.lower() or value.strip() == "":
                return match.group(0)
            replacement = "!!!@@@" if re.search(r"[^\w\s]", value) else "recherche-introuvable-zz"
            return f'{match.group(1)}{replacement}{match.group(3)}'

        gherkin = re.sub(
            r'(saisit\s+")([^"]+)("\s+dans\s+(?:le\s+)?(?:champ|zone|input|bo[îi]te))',
            replace_contradictory_input,
            gherkin,
            flags=re.IGNORECASE
        )

    scenario["senario"] = _clean_gherkin_text(gherkin)
    return scenario


def _clean_gherkin_text(gherkin: str) -> str:
    gherkin = re.sub(r'\[/USER\].*', '', gherkin, flags=re.DOTALL)
    gherkin = re.sub(r'\[/ASSISTANT\].*', '', gherkin, flags=re.DOTALL)
    return gherkin.strip()


def _infer_scenario_type(text: str) -> str:
    lower = text.lower()
    if any(k in lower for k in ["sécurité", "securite", "permission", "autorisation", "accès refusé", "unauthorized", "forbidden"]):
        return "Sécurité"
    if any(k in lower for k in ["performance", "temps de chargement", "latence", "moins de"]):
        return "Performance"
    if any(k in lower for k in ["invalide", "incorrect", "erreur", "échoué", "refus", "vide", "obligatoire"]):
        return "Négatif"
    if any(k in lower for k in ["validation", "format", "limite", "longueur", "min", "max"]):
        return "Validation"
    if any(k in lower for k in ["navigation", "lien", "url", "redirig"]):
        return "Navigation"
    if any(k in lower for k in ["accessibilité", "accessibilite", "lisible", "aria", "clavier", "focus"]):
        return "Accessibilité"
    return "Fonctionnel"


def _deduplicate_scenarios(scenarios: List[Dict]) -> List[Dict]:
    unique = []
    seen = set()
    for scenario in scenarios:
        title = str(scenario.get("nomSenario", "")).strip()
        gherkin = str(scenario.get("senario", "")).strip()
        if not title or not gherkin:
            continue
        key_source = f"{title}|{gherkin[:300]}".lower()
        key = re.sub(r'[^a-z0-9à-ÿ]+', '', key_source)
        if key in seen:
            continue
        seen.add(key)
        scenario.setdefault("selected", True)
        scenario.setdefault("type", _infer_scenario_type(f"{title} {gherkin}"))
        unique.append(scenario)
    return unique


_FOCUS_STOPWORDS = {
    "test", "tester", "scenario", "scénario", "cas", "avec", "pour", "dans", "sur",
    "une", "des", "les", "le", "la", "un", "du", "de", "d", "et", "ou", "au", "aux",
    "champ", "bouton", "formulaire", "page", "application", "verifier", "vérifier",
    "faire", "seulement", "uniquement", "objectif", "focus"
}


def _normalize_focus_objective(value: Optional[str]) -> str:
    return str(value or "").strip()


def _normalize_for_match(value: Any) -> str:
    normalized = unicodedata.normalize("NFD", str(value or "").lower())
    return "".join(ch for ch in normalized if unicodedata.category(ch) != "Mn")


def _focus_tokens(focus_objective: str) -> List[str]:
    normalized = _normalize_for_match(focus_objective)
    tokens = re.findall(r"[a-z0-9]+", normalized)
    return [token for token in tokens if len(token) > 2 and token not in _FOCUS_STOPWORDS]


def _filter_elements_by_focus(elements_dicts: List[Dict], focus_objective: str) -> List[Dict]:
    """Keep only elements that look related to the optional test objective."""
    tokens = _focus_tokens(focus_objective)
    if not tokens:
        return elements_dicts

    matched = []
    support_elements = []
    for el in elements_dicts:
        haystack = _normalize_for_match(" ".join(str(el.get(key, "")) for key in [
            "tag", "type", "name", "id", "text", "placeholder", "aria-label", "role", "data-testid", "href"
        ]))
        if any(token in haystack for token in tokens):
            matched.append(el)
        elif _is_button_like(el) or (el.get("tag") or "").lower() in {"select", "textarea"}:
            support_elements.append(el)

    if not matched:
        return elements_dicts

    return _deduplicate_elements(matched + support_elements)


def _deduplicate_elements(elements: List[Dict]) -> List[Dict]:
    unique = []
    seen = set()
    for el in elements:
        key = (el.get("id") or "", el.get("name") or "", el.get("text") or "", el.get("tag") or "")
        if key in seen:
            continue
        seen.add(key)
        unique.append(el)
    return unique


def _scenario_matches_focus(scenario: Dict, focus_objective: str) -> bool:
    tokens = _focus_tokens(focus_objective)
    if not tokens:
        return True

    haystack = _normalize_for_match(" ".join(str(scenario.get(key, "")) for key in [
        "nomSenario", "description", "resultatAttendu", "type", "senario"
    ]))
    score = sum(1 for token in tokens if token in haystack)
    threshold = 1 if len(tokens) == 1 else min(2, len(tokens))
    return score >= threshold


def _filter_scenarios_by_focus(scenarios: List[Dict], focus_objective: str) -> List[Dict]:
    if not focus_objective:
        return scenarios
    return [scenario for scenario in scenarios if _scenario_matches_focus(scenario, focus_objective)]


def _short_focus(focus_objective: str) -> str:
    return re.sub(r"\s+", " ", focus_objective).strip()[:45]


def _focus_mentions(focus_objective: str, keywords: List[str]) -> bool:
    focus = _normalize_for_match(focus_objective)
    return any(_normalize_for_match(keyword) in focus for keyword in keywords)


def _generate_scenarios_with_ai(url: str, elements_description: str, elements_dicts: List[Dict], focus_objective: str = "") -> List[Dict]:
    """
    Use Qwen/Hugging Face to generate Gherkin scenarios dynamically based on page analysis
    """
    try:
        focus_instruction = """
MODE CIBLÉ ACTIVÉ:
- L'utilisateur a donné un Focus / Objectif du test.
- Génère UNIQUEMENT des scénarios liés à cet objectif.
- Si l'objectif décrit un seul scénario précis, génère 1 seul scénario.
- Si l'objectif est plus large, génère maximum 4 variantes strictement liées.
- N'ajoute pas de tests de navigation, accessibilité ou champs sans rapport direct avec l'objectif.
""" if focus_objective else """
MODE COMPLET ACTIVÉ:
- Aucun focus n'est fourni.
- Génère tous les scénarios pertinents possibles à partir des éléments détectés.
"""

        system_prompt = f"""Tu es un expert QA senior spécialisé dans la génération automatique de scénarios de test exécutables.
Basé sur l'analyse des éléments détectés sur une page web, tu dois générer des cas de test réalistes et complets en format Gherkin.

{focus_instruction}

INSTRUCTIONS CRITIQUES:
1. En mode complet, génère entre 12 et 18 scénarios si la page contient assez d'éléments interactifs. En mode ciblé, respecte strictement le focus.
2. Les scénarios doivent être différents: parcours nominal, champs obligatoires, formats invalides, limites, navigation, liens, boutons, accessibilité, erreurs visibles, régression.
3. Chaque scénario doit utiliser des données de test précises: exemples d'email, mot de passe faible/fort, texte long, champ vide, recherche inexistante, numéro invalide, etc.
4. Base-toi UNIQUEMENT sur les éléments listés dans la Page Analysis. N'invente pas de locator.
5. UTILISE TOUJOURS les sélecteurs concrets: Pour chaque action (When/And), privilégie `(id: identifiant)` ou `(name: valeur)` listé dans la 'Page Analysis'. Ex: `When l'utilisateur clique sur le bouton "Search" (id: search-icon-legacy)`.
   Si aucun id/name n'existe, utilise `(css: textarea)`, `(css: [role='textbox'])`, `(css: [role='combobox'])` ou `(xpath: //textarea)`.
   ÉVITE le format `(role: ...)` dans les nouveaux scénarios sauf si aucun autre locator n'est possible.
   Pour les champs sensibles, sois strict: username/login doit utiliser le locator du champ username, password/mot de passe doit utiliser le locator du champ password. Ne réutilise jamais le locator username pour le password.
   Si un champ password a un `id` ou `name`, utilise toujours `(id: ...)` ou `(name: ...)`; n'utilise `(css: input[type='password'])` que si aucun id/name n'existe.
   Pour un champ de recherche, n'invente jamais `(name: search)`. Utilise seulement le vrai `id`/`name` listé; sinon utilise un locator générique robuste comme `(css: input[type='search'])`, `(css: [role='searchbox'])`, ou `(css: form[action*='search' i] input)`.
6. VALIDATION (Then): Utilise EXCLUSIVEMENT ces formats reconnus par l'automate :
   - `Then l'élément (id: identifiant) devrait être visible`
   - `Then l'élément (id: identifiant) ne devrait pas être visible`
   - `Then l'URL de la page devrait être "https://..."`
   - `Then l'URL devrait contenir "mot-clé"` (utilise le terme recherché seul, ex: "Kindle" pas "s=Kindle")
   - `Then l'URL de la page ne devrait pas contenir "mot-clé"` uniquement si la donnée saisie ne contient PAS ce mot-clé.
   - `Then le texte "mot" devrait être visible`
    NE JAMAIS utiliser des phrases abstraites.
7. ÉTAPE GIVEN : Utilise TOUJOURS ce format exact :
   `Given l'utilisateur se trouve sur la page "https://url"`
8. RÈGLES DE COHÉRENCE:
   - Pour un scénario négatif de recherche, n'utilise jamais une donnée comme `Nabeul!@` si l'assertion dit que l'URL ne doit pas contenir `Nabeul`; utilise plutôt `!!!@@@` ou `recherche-introuvable-zz`.
   - Pour les sites de traduction, n'invente pas de traduction. N'écris jamais les textes RTL à l'envers: utilise la forme affichée normalement, ex: `مرحبا`, jamais une chaîne inversée.
   - Ignore les boutons de navigation globale/header qui ne contrôlent pas directement le widget testé; utilise seulement les champs, boutons et menus liés au formulaire ou composant principal.
   - Si aucun locator fiable de sélection de langue/option n'est listé, évite les étapes manuelles de choix et teste plutôt la saisie + visibilité du champ/résultat.
9. Format EXACT (crucial pour le parsing):

### SCENARIO: [Nom du scénario]
DESCRIPTION: [Brève description]
EXPECTED: [Résultat attendu]
GHERKIN:
Feature: [Nom du feature]
  Scenario: [Nom du scénario]
    Given l'utilisateur se trouve sur la page "https://..."
    When [action détaillée avec locateur (id: ...)]
    And [action supplémentaire avec locateur]
    Then [validation concrète avec format reconnu]

10. NE RAJOUTE PAS d'explications, uniquement les scénarios."""

        user_prompt = f"""Page à tester: {url}

Focus / Objectif du test: {focus_objective or 'Aucun - couvrir tous les scénarios pertinents'}

{elements_description}

Génère maintenant des scénarios de test pertinents pour cette page en suivant le format EXACT imposé."""

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]

        print(f"🤖 Calling Qwen for scenario generation...")
        response = hf_chat_completion(
            messages=messages,
            max_tokens=5000,
            temperature=0.45
        )

        generated_text = response.choices[0].message.content.strip()
        print(f"✓ Qwen response received ({len(generated_text)} chars)")

        scenarios = _parse_scenarios_from_generated_text(generated_text, selected_default=True, source="IA_DOM")
        if focus_objective:
            return _ensure_minimum_scenarios(url, elements_dicts, scenarios, FOCUSED_MIN_SCENARIOS, focus_objective, FOCUSED_MAX_SCENARIOS)
        return _ensure_minimum_scenarios(url, elements_dicts, scenarios, MIN_DYNAMIC_SCENARIOS, "", MAX_DYNAMIC_SCENARIOS)

    except Exception as e:
        print(f"❌ AI scenario generation failed: {str(e)}")
        return []


def _ensure_minimum_scenarios(
    url: str,
    elements_dicts: List[Dict],
    scenarios: List[Dict],
    minimum: int,
    focus_objective: str = "",
    max_count: int = MAX_DYNAMIC_SCENARIOS
) -> List[Dict]:
    """Keep AI scenarios, then add deterministic scenarios until the set is useful."""
    merged = _deduplicate_scenarios(scenarios or [])
    if focus_objective:
        merged = _filter_scenarios_by_focus(merged, focus_objective)

    if len(merged) < minimum:
        needed = minimum - len(merged)
        merged.extend(_build_targeted_supplemental_scenarios(url, elements_dicts, needed, focus_objective))

    return _limit_diverse_scenarios(_deduplicate_scenarios(merged), max_count)


def _build_targeted_supplemental_scenarios(url: str, elements_dicts: List[Dict], needed: int, focus_objective: str = "") -> List[Dict]:
    """Generate concrete extra scenarios from detected DOM elements when the LLM under-produces."""
    scenarios: List[Dict] = []
    elements_dicts = _filter_elements_by_focus(elements_dicts, focus_objective)
    given = f"Given l'utilisateur se trouve sur la page \"{_safe_gherkin_value(url)}\""
    url_token = _url_contains_token(url)

    if not focus_objective or _focus_mentions(focus_objective, ["navigation", "chargement", "page", "url", "lien"]):
        scenarios.append(_scenario_dict(
            "Navigation - Chargement de la page cible",
            "Navigation",
            "Vérifier que la page analysée reste accessible avant toute interaction.",
            "L'URL courante correspond à la page cible.",
            f'''Feature: Navigation
  Scenario: Chargement de la page cible
    {given}
    Then l'URL devrait contenir "{url_token}"'''
        ))

    fillable_tags = {"input", "textarea"}
    fillable_types = {"", "text", "email", "password", "tel", "number", "search", "url"}
    inputs = [el for el in elements_dicts if (el.get("tag") or "").lower() in fillable_tags]
    buttons = [el for el in elements_dicts if _is_button_like(el)]
    links = [el for el in elements_dicts if (el.get("tag") or "").lower() == "a"]
    selects = [el for el in elements_dicts if (el.get("tag") or "").lower() == "select"]

    for el in inputs[:10]:
        locator = _locator_hint(el)
        if not locator:
            continue
        label = _element_display_name(el)
        tag = (el.get("tag") or "").lower()
        field_type = (el.get("type") or "").lower()

        scenarios.append(_scenario_dict(
            f"Accessibilité - Champ visible - {label}",
            "Accessibilité",
            f"Vérifier que le champ '{label}' est visible et ciblable par l'automate.",
            f"Le champ '{label}' est visible.",
            f"""Feature: Accessibilité formulaire
  Scenario: Champ visible - {label}
    {given}
    Then l'élément ({locator}) devrait être visible"""
        ))

        if tag in fillable_tags and field_type in fillable_types:
            test_value = _test_value_for_field(el)
            scenarios.append(_scenario_dict(
                f"Fonctionnel - Saisie valide - {label}",
                "Fonctionnel",
                f"Vérifier que le champ '{label}' accepte une valeur valide spécifique.",
                f"La saisie dans '{label}' est acceptée et le champ reste disponible.",
                f"""Feature: Saisie formulaire
  Scenario: Saisie valide - {label}
    {given}
    When l'utilisateur saisit "{test_value}" dans le champ "{label}" ({locator})
    Then l'élément ({locator}) devrait être visible"""
            ))

        if field_type == "email":
            scenarios.append(_scenario_dict(
                f"Validation - Email invalide - {label}",
                "Validation",
                f"Vérifier le comportement du champ email '{label}' avec une valeur invalide.",
                "La valeur invalide ne doit pas permettre une soumission correcte.",
                f"""Feature: Validation formulaire
  Scenario: Email invalide - {label}
    {given}
    When l'utilisateur saisit "email-invalide" dans le champ "{label}" ({locator})
    Then l'élément ({locator}) devrait être visible"""
            ))

        if field_type == "password":
            scenarios.append(_scenario_dict(
                f"Validation - Mot de passe faible - {label}",
                "Validation",
                f"Vérifier le comportement du champ mot de passe '{label}' avec une valeur faible.",
                "Le champ reste visible pour permettre la correction du mot de passe.",
                f"""Feature: Validation formulaire
  Scenario: Mot de passe faible - {label}
    {given}
    When l'utilisateur saisit "123" dans le champ "{label}" ({locator})
    Then l'élément ({locator}) devrait être visible"""
            ))

        if str(el.get("required", "")).lower() == "true":
            scenarios.append(_scenario_dict(
                f"Négatif - Champ obligatoire vide - {label}",
                "Négatif",
                f"Vérifier que le champ obligatoire '{label}' ne peut pas être ignoré.",
                "Le champ obligatoire reste visible pour correction.",
                f"""Feature: Validation formulaire
  Scenario: Champ obligatoire vide - {label}
    {given}
    When l'utilisateur laisse le champ "{label}" vide ({locator})
    Then l'élément ({locator}) devrait être visible"""
            ))

        if len(scenarios) >= needed + 1:
            break

    for el in selects[:4]:
        locator = _locator_hint(el)
        if not locator:
            continue
        label = _element_display_name(el)
        scenarios.append(_scenario_dict(
            f"Fonctionnel - Sélection liste - {label}",
            "Fonctionnel",
            f"Vérifier que la liste '{label}' est disponible pour sélection.",
            f"La liste '{label}' reste visible après l'interaction.",
            f"""Feature: Sélection formulaire
  Scenario: Sélection liste - {label}
    {given}
    When l'utilisateur sélectionne "1" dans la liste déroulante "{label}" ({locator})
    Then l'élément ({locator}) devrait être visible"""
        ))

    for el in buttons[:8]:
        locator = _locator_hint(el)
        if not locator:
            continue
        label = _element_display_name(el)
        scenarios.append(_scenario_dict(
            f"Accessibilité - Bouton visible - {label}",
            "Accessibilité",
            f"Vérifier que le bouton '{label}' est visible avant interaction.",
            f"Le bouton '{label}' est visible.",
            f"""Feature: Boutons
  Scenario: Bouton visible - {label}
    {given}
    Then l'élément ({locator}) devrait être visible"""
        ))

    for el in links[:6]:
        locator = _locator_hint(el)
        href = el.get("href") or ""
        if not locator or not href:
            continue
        label = _element_display_name(el)
        expected_token = _url_contains_token(href)
        scenarios.append(_scenario_dict(
            f"Navigation - Lien accessible - {label}",
            "Navigation",
            f"Vérifier que le lien '{label}' est détecté et exploitable.",
            f"Le lien '{label}' mène vers une URL cohérente.",
            f'''Feature: Navigation liens
  Scenario: Lien accessible - {label}
    {given}
    When l'utilisateur clique sur le lien "{label}" ({locator})
    Then l'URL devrait contenir "{expected_token}"'''
        ))

    deduped = _deduplicate_scenarios(scenarios)
    if focus_objective:
        focused = _filter_scenarios_by_focus(deduped, focus_objective)
        if focused:
            return _limit_diverse_scenarios(focused, max(needed, 1))

        short_focus = _short_focus(focus_objective)
        for scenario in deduped:
            scenario["nomSenario"] = f"Focus - {short_focus} - {scenario.get('nomSenario', '')}"
            scenario["description"] = f"Scénario complémentaire limité au focus: {short_focus}. {scenario.get('description', '')}"

    return _limit_diverse_scenarios(deduped, max(needed, 1))


def _limit_diverse_scenarios(scenarios: List[Dict], limit: int) -> List[Dict]:
    """Prefer a mix of test types instead of returning only the first field scenarios."""
    if len(scenarios) <= limit:
        return scenarios

    type_order = ["Navigation", "Fonctionnel", "Validation", "Négatif", "Accessibilité", "Sécurité", "Performance"]
    grouped: Dict[str, List[Dict]] = {scenario_type: [] for scenario_type in type_order}
    grouped["Autre"] = []

    for scenario in scenarios:
        scenario_type = scenario.get("type", "Autre")
        grouped.setdefault(scenario_type, [])
        grouped[scenario_type].append(scenario)

    selected = []
    while len(selected) < limit:
        added = False
        for scenario_type in type_order + [t for t in grouped.keys() if t not in type_order]:
            if grouped.get(scenario_type):
                selected.append(grouped[scenario_type].pop(0))
                added = True
                if len(selected) == limit:
                    break
        if not added:
            break

    return selected


def _scenario_dict(title: str, scenario_type: str, description: str, expected: str, gherkin: str) -> Dict:
    return {
        "nomSenario": title,
        "description": description,
        "resultatAttendu": expected,
        "type": scenario_type,
        "senario": _clean_gherkin_text(gherkin),
        "selected": True,
        "source": "COMPLEMENT_DOM"
    }


def _locator_hint(el: Dict) -> str:
    for key in ("id", "name"):
        value = str(el.get(key) or "").strip()
        if value:
            return f"{key}: {_safe_locator_value(value)}"

    tag = (el.get("tag") or "").lower()
    field_type = (el.get("type") or "").lower()
    if tag == "input" and field_type in {"password", "email", "search", "tel", "number"}:
        return f"css: input[type='{_safe_locator_value(field_type)}']"

    for key in ("data-testid", "data-test"):
        value = str(el.get(key) or "").strip()
        if value and " " not in value and "'" not in value:
            return f"css: [{key}='{_safe_locator_value(value)}']"

    return ""


def _safe_locator_value(value: str) -> str:
    return re.sub(r'[\r\n\t]+', '', value).strip()[:80]


def _element_display_name(el: Dict) -> str:
    for key in ("text", "placeholder", "aria-label", "name", "id", "data-testid", "tag"):
        value = str(el.get(key) or "").strip()
        if value:
            return re.sub(r'\s+', ' ', value)[:45]
    return "élément"


def _test_value_for_field(el: Dict) -> str:
    field_type = (el.get("type") or "").lower()
    name = f"{el.get('name', '')} {el.get('id', '')} {el.get('placeholder', '')}".lower()
    if field_type == "email" or "email" in name:
        return "qa.user@example.com"
    if field_type == "password" or "pass" in name:
        return "TestPassword123!"
    if field_type == "number":
        return "42"
    if field_type == "tel" or "phone" in name or "tel" in name:
        return "+21612345678"
    if field_type == "search" or "search" in name or "query" in name:
        return "produit test"
    if field_type == "url":
        return "https://example.com"
    return "valeur de test automatique"


def _is_button_like(el: Dict) -> bool:
    tag = (el.get("tag") or "").lower()
    field_type = (el.get("type") or "").lower()
    role = (el.get("role") or "").lower()
    return tag == "button" or field_type in {"button", "submit"} or role == "button"


def _safe_gherkin_value(value: str) -> str:
    return str(value or "").replace("\\", "\\\\").replace('"', '\\"').strip()


def _url_contains_token(url: str) -> str:
    raw_url = str(url or "")
    if raw_url.startswith("file:"):
        return "file"
    match = re.search(r'https?://([^/]+)', raw_url)
    if match:
        return match.group(1).replace("www.", "")[:60]
    cleaned = re.sub(r'[^A-Za-z0-9_-]+', ' ', raw_url).strip().split()
    return cleaned[0][:60] if cleaned else "http"


def _get_fallback_scenarios(url: str = "") -> List[Dict]:
    """
    Return basic fallback scenarios if AI generation fails
    """
    given = f"Given l'utilisateur se trouve sur la page \"{_safe_gherkin_value(url)}\"" if url else "Given l'utilisateur se trouve sur la page"
    url_token = _url_contains_token(url)
    return [
        {
            "nomSenario": "Navigation - Vérifier le chargement",
            "type": "Navigation",
            "description": "Vérifier que la page charge correctement",
            "resultatAttendu": "La page s'affiche sans erreurs 404/500",
            "senario": f"""Feature: Navigation
  Scenario: Charger la page
    {given}
    Then l'URL devrait contenir "{url_token}"
""",
            "selected": True,
            "source": "FALLBACK"
        }
    ]


@app.post("/api/execute-scenarios")
async def execute_scenarios(request: ExecuteScenarioRequest):
    """
    Execute selected Gherkin scenarios in real Selenium browser
    Generates PDF report with screenshots and detailed results
    """

    if not request.url:
        raise HTTPException(status_code=400, detail="URL is required")
    if not request.scenarios:
        raise HTTPException(status_code=400, detail="At least one scenario is required")

    # Filter selected scenarios
    selected_scenarios = [s for s in request.scenarios if s.selected]
    if not selected_scenarios:
        raise HTTPException(status_code=400, detail="No scenarios selected for execution")

    execution_logs = []
    def log_step(message: str):
        """Log each step and print it"""
        execution_logs.append(message)
        logger.info(f"[EXECUTION] {message}")
        print(f"[EXECUTION] {message}")

    log_step(f"🚀 Starting execution of {len(selected_scenarios)} scenarios...")

    # DEBUG: Log the URL being received
    print(f"\n🔍 DEBUG URL INFO:")
    print(f"   request.url type: {type(request.url)}")
    print(f"   request.url value: '{request.url}'")
    print(f"   request.url length: {len(request.url) if request.url else 0}")
    print(f"   request.url repr: {repr(request.url)}")
    print()

    log_step(f"URL from request: {request.url}")

    executor = GherkinExecutor(headless=False)  # Mode visible!
    scenarios_results = []
    start_time_total = __import__('time').time()

    try:
        # Start Chrome WebDriver
        log_step("Opening Chrome browser (VISIBLE mode)...")
        executor.start_driver()
        log_step("✓ Chrome WebDriver initialized and visible")

        # Execute each selected scenario
        for idx, scenario in enumerate(selected_scenarios, 1):
            log_step(f"\n[{idx}/{len(selected_scenarios)}] Executing: {scenario.nomSenario}")

            try:
                result = executor.execute_scenario(
                    scenario_name=scenario.nomSenario,
                    gherkin_text=scenario.senario,
                    base_url=request.url
                )

                # Convert dataclass to dict
                result_dict = {
                    "scenario_name": result.scenario_name,
                    "nomSenario": result.scenario_name,
                    "status": result.status,
                    "duration_ms": result.duration_ms,
                    "steps": [
                        {
                            "step_text": step.step_text,
                            "action": step.step_text,
                            "status": step.status,
                            "duration_ms": step.duration_ms,
                            "screenshot": step.screenshot_base64,
                            "error_message": step.error_message
                        }
                        for step in result.steps
                    ],
                    "screenshots": result.screenshots,
                    "error_message": result.error_message
                }

                scenarios_results.append(result_dict)

                status_emoji = "✓" if result.status == "PASSED" else "✗"
                log_step(f"  {status_emoji} {result.status} ({result.duration_ms}ms, {len(result.steps)} steps)")

            except Exception as e:
                error_msg = f"Scenario execution error: {str(e)}"
                log_step(f"  ✗ ERROR: {error_msg}")
                scenarios_results.append({
                    "scenario_name": scenario.nomSenario,
                    "nomSenario": scenario.nomSenario,
                    "status": "ERROR",
                    "duration_ms": 0,
                    "steps": [],
                    "screenshots": [],
                    "error_message": error_msg
                })

        # Calculate summary
        total_duration_ms = int((__import__('time').time() - start_time_total) * 1000)
        total_scenarios = len(scenarios_results)
        passed_scenarios = sum(1 for s in scenarios_results if s.get("status") == "PASSED")
        failed_scenarios = sum(1 for s in scenarios_results if s.get("status") != "PASSED")

        log_step(f"\n📊 Execution Summary:")
        log_step(f"   Total: {total_scenarios} | Passed: {passed_scenarios} | Failed: {failed_scenarios}")
        log_step(f"   Duration: {total_duration_ms}ms ({total_duration_ms/1000:.1f}s)")

        # Generate rich report
        log_step("\n📋 Generating report...")
        project_metadata = {
            "project_name": request.projectId,
            "url": request.url,
            "timestamp": __import__('datetime').datetime.now().isoformat()
        }

        report = generate_rich_report(scenarios_results, project_metadata)
        log_step("✓ Report generated successfully")

        return {
            "status": "COMPLETED",
            "summary": {
                "total": total_scenarios,
                "passed": passed_scenarios,
                "failed": failed_scenarios,
                "duration_ms": total_duration_ms,
                "started_at": __import__('datetime').datetime.now().isoformat()
            },
            "scenarios_results": scenarios_results,
            "report_html": report["html"],
            "report_base64_pdf": report["pdf_base64"],
            "report_summary": report["summary"],
            "pdf_generation_mode": report.get("pdf_generation_mode", "unknown"),
            "execution_logs": execution_logs  # Send logs to frontend
        }

    except Exception as e:
        error_msg = f"Execution pipeline error: {str(e)}"
        log_step(f"\n✗ {error_msg}")
        raise HTTPException(status_code=500, detail=error_msg)

    finally:
        # Cleanup
        log_step("\n⏸️  Keeping Chrome window open for 3 seconds...")
        __import__('time').sleep(3)  # Let user see the final state
        executor.stop_driver()
        log_step("\n✓ Cleanup completed")


# ================== HTML FILE ANALYSIS (BeautifulSoup) ==================

class AnalyzeHtmlRequest(BaseModel):
    html_content: str

@app.post("/api/analyze-html")
async def analyze_html_endpoint(request: AnalyzeHtmlRequest):
    """
    Analyse un fichier HTML via BeautifulSoup.
    Retourne les champs détectés et les comportements détectés.
    """
    if not request.html_content or not request.html_content.strip():
        raise HTTPException(status_code=400, detail="Le contenu HTML est vide.")

    try:
        print(f"[HTML-ANALYZER] Analyse en cours ({len(request.html_content)} caractères)...")
        result = analyze_html(request.html_content)
        print(f"[HTML-ANALYZER] ✓ {result['summary']['total_fields']} champs, {result['summary']['total_behaviors']} comportements détectés")
        return result
    except Exception as e:
        print(f"[HTML-ANALYZER] ✗ Erreur: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Erreur d'analyse HTML: {str(e)}")


# ================== FILE SELENIUM: CodeT5 Script Generation + Execution ==================

class GenerateFileSeleniumRequest(BaseModel):
    fields: List[Dict[str, Any]]
    tests: List[Dict[str, Any]]
    html_content: str

class RunFileSeleniumRequest(BaseModel):
    script_code: str
    html_content: str   # base64 encoded HTML
    tests: List[Dict[str, Any]] = []   # Liste des tests générés (pour exécution individuelle)
    fields: List[Dict[str, Any]] = []  # Champs analysés (pour génération de scripts par test)


@app.post("/api/generate-file-selenium")
async def generate_file_selenium(request: GenerateFileSeleniumRequest):
    """
    Génère un script Selenium Python via le modèle CodeT5 finetuné local.
    Entrée : champs extraits (BeautifulSoup) + tests générés + contenu HTML.
    Sortie : code Selenium Python prêt à exécuter.
    """
    if not codet5_model or not codet5_tokenizer:
        raise HTTPException(status_code=503, detail="Le modèle CodeT5 local n'est pas chargé.")

    if not request.fields and not request.tests:
        raise HTTPException(status_code=400, detail="Aucun champ ni test fourni.")

    try:
        # 1. Construire un prompt structuré pour CodeT5 à partir des champs et tests
        elements_summary = []
        for field in request.fields:
            tag = field.get('tag', '')
            field_type = field.get('type', '')
            field_id = f"#{field.get('id')}" if field.get('id') else ""
            field_name = f"[name={field.get('name')}]" if field.get('name') else ""
            elements_summary.append(f"{tag}{field_id}{field_name}")

        # Construire l'objectif à partir des tests générés
        test_names = [t.get('nomSenario', '') for t in request.tests[:5]]  # max 5 pour garder le prompt court
        objective = ", ".join(test_names) if test_names else "test form interactions"

        prompt_text = f"Generate Selenium script for: form_test: {', '.join(elements_summary[:10])} | {objective}"
        
        print(f"[CodeT5-FileSelenium] Prompt: {prompt_text[:120]}...")

        # 2. Génération via CodeT5 local finetuné
        inputs = codet5_tokenizer(prompt_text, return_tensors="pt", max_length=128, truncation=True)
        outputs = codet5_model.generate(
            inputs.input_ids,
            max_length=256,
            num_beams=4,
            early_stopping=True
        )
        generated_code = codet5_tokenizer.decode(outputs[0], skip_special_tokens=True)

        # 3. Construire le script complet avec setup Selenium + code CodeT5
        # Génération d'actions de test supplémentaires basées sur les champs détectés
        field_actions = []
        for field in request.fields:
            tag = field.get('tag', '')
            f_id = field.get('id', '')
            f_name = field.get('name', '')
            f_type = field.get('type', '')
            f_placeholder = field.get('placeholder', '')
            f_required = field.get('required', False)
            
            locator = ""
            locator_type = ""
            if f_id:
                locator = f_id
                locator_type = "By.ID"
            elif f_name:
                locator = f_name
                locator_type = "By.NAME"
            else:
                continue
            
            if tag == 'input' and f_type in ('text', 'email', 'password', 'tel', 'number', 'search'):
                test_value = f_placeholder or f"test_{f_name or f_id}"
                if f_type == 'email':
                    test_value = "test@example.com"
                elif f_type == 'password':
                    test_value = "TestPassword123!"
                elif f_type == 'number':
                    test_value = "42"
                elif f_type == 'tel':
                    test_value = "+21612345678"
                field_actions.append(f"# Remplir le champ {f_name or f_id} ({f_type})")
                field_actions.append(f"elem = driver.find_element({locator_type}, '{locator}')")
                field_actions.append(f"elem.clear()")
                field_actions.append(f"elem.send_keys('{test_value}')")
                if f_required:
                    field_actions.append(f"assert elem.get_attribute('value') != '', 'Le champ {f_name or f_id} est vide'")
                field_actions.append("")
            elif tag == 'select':
                field_actions.append(f"# Sélectionner une option dans {f_name or f_id}")
                field_actions.append(f"from selenium.webdriver.support.ui import Select")
                field_actions.append(f"select_elem = Select(driver.find_element({locator_type}, '{locator}'))")
                field_actions.append(f"if len(select_elem.options) > 1:")
                field_actions.append(f"    select_elem.select_by_index(1)")
                field_actions.append("")
            elif tag == 'textarea':
                field_actions.append(f"# Remplir le textarea {f_name or f_id}")
                field_actions.append(f"textarea = driver.find_element({locator_type}, '{locator}')")
                field_actions.append(f"textarea.clear()")
                field_actions.append(f"textarea.send_keys('Texte de test automatique')")
                field_actions.append("")
            elif tag == 'button' or (tag == 'input' and f_type == 'submit'):
                field_actions.append(f"# Cliquer sur le bouton {field.get('text', f_name or f_id)}")
                field_actions.append(f"btn = driver.find_element({locator_type}, '{locator}')")
                field_actions.append(f"assert btn.is_displayed(), 'Le bouton {f_name or f_id} n\\'est pas visible'")
                field_actions.append(f"# btn.click()  # Décommenter pour soumettre le formulaire")
                field_actions.append("")

        field_actions_code = "\n".join(field_actions) if field_actions else "# Aucune action de champ générée"

        # Construire le script via concaténation (PAS d'f-string) pour éviter les conflits
        # avec le code généré par CodeT5 qui peut contenir des accolades

        # Mettre le code CodeT5 en commentaire (il est souvent tronqué par le max_length du modèle)
        codet5_commented = "\n".join(["# CodeT5> " + line for line in generated_code.strip().split("\n") if line.strip()])

        final_script = """# ================================================================
# Script Selenium Genere par CodeT5 (Modele ML Local Finetune)
# Fichier: Test automatique des champs detectes
# ================================================================
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.options import Options
import time

# === Setup du navigateur Chrome (mode visible) ===
chrome_options = Options()
chrome_options.add_argument("--start-maximized")
chrome_options.add_argument("--disable-blink-features=AutomationControlled")

driver = webdriver.Chrome(options=chrome_options)
driver.implicitly_wait(10)

# === Navigation vers le fichier HTML ===
driver.get('{FILE_URL}')
time.sleep(2)

print("Page chargee avec succes")
print("Titre: " + driver.title)

# === Suggestion CodeT5 (reference) ===
""" + codet5_commented + """

# === Actions de test sur les champs detectes ===
""" + field_actions_code + """

# === Verifications finales ===
print("URL courante: " + driver.current_url)
print("Tous les tests ont ete executes avec succes!")

time.sleep(3)
"""
        print(f"[CodeT5-FileSelenium] ✓ Script généré ({len(final_script)} chars)")

        return {
            "selenium_code": final_script,
            "file_name": "test_file_selenium.py",
            "codet5_raw": generated_code.strip(),
            "fields_count": len(request.fields),
            "tests_count": len(request.tests)
        }

    except Exception as e:
        print(f"[CodeT5-FileSelenium] ✗ Erreur: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Erreur génération CodeT5: {str(e)}")


def _generate_single_test_script(test: Dict[str, Any], fields: List[Dict[str, Any]], file_url: str) -> str:
    """Génère un script Selenium ciblé pour UN SEUL test case."""
    test_name = test.get('nomSenario', test.get('name', 'Test'))
    test_desc = test.get('description', '')
    name_lower = test_name.lower()
    # Échapper les apostrophes pour éviter les SyntaxError dans le code Python généré
    safe_test_name = test_name.replace("'", "\\'")

    # Déterminer le type de test et les actions correspondantes
    field_actions = []
    for field in fields:
        tag = field.get('tag', '')
        f_id = field.get('id', '')
        f_name = field.get('name', '')
        f_type = field.get('type', '')
        f_required = field.get('required', False)

        locator = f_id or f_name
        locator_type = "By.ID" if f_id else "By.NAME"
        if not locator:
            continue

        if tag == 'input' and f_type in ('text', 'email', 'password', 'tel', 'number', 'search'):
            # Adapter la valeur selon le type de test
            if 'incomplet' in name_lower or 'vide' in name_lower or 'formulaire incomplet' in name_lower:
                # Test formulaire incomplet: ne pas remplir les champs
                field_actions.append(f"# Champ {f_name or f_id} laissé vide intentionnellement")
                continue
            elif ('mot de passe non conforme' in name_lower or 'password' in name_lower.replace('mot de passe', 'password')) and f_type == 'password':
                if 'incorrect' in name_lower or 'confirmation' in name_lower:
                    test_value = "DifferentPass456!"
                elif 'non conforme' in name_lower or 'faible' in name_lower:
                    test_value = "123"
                else:
                    test_value = "TestPassword123!"
            elif f_type == 'email':
                if 'invalide' in name_lower or 'email' in name_lower and 'invalide' in name_lower:
                    test_value = "invalid-email"
                else:
                    test_value = "test@example.com"
            elif f_type == 'password':
                test_value = "TestPassword123!"
            elif f_type == 'number':
                test_value = "42"
            elif f_type == 'tel':
                test_value = "+21612345678"
            else:
                test_value = f"test_{f_name or f_id}"

            field_actions.append(f"elem = driver.find_element({locator_type}, '{locator}')")
            field_actions.append(f"elem.clear()")
            field_actions.append(f"elem.send_keys('{test_value}')")
            safe_field = (f_name or f_id).replace("'", "\\'")
            field_actions.append(f"print('STEP_PASS: Champ {safe_field} rempli avec succes')")
            field_actions.append("")

        elif tag == 'select':
            if 'incomplet' not in name_lower and 'vide' not in name_lower:
                field_actions.append(f"from selenium.webdriver.support.ui import Select")
                field_actions.append(f"sel = Select(driver.find_element({locator_type}, '{locator}'))")
                field_actions.append(f"if len(sel.options) > 1: sel.select_by_index(1)")
                safe_field = (f_name or f_id).replace("'", "\\'")
                field_actions.append(f"print('STEP_PASS: Select {safe_field} selectionne')")
                field_actions.append("")

        elif tag == 'button' or (tag == 'input' and f_type == 'submit'):
            if 'connexion' in name_lower and 'connexion' not in (field.get('text', '') or '').lower():
                continue
            field_actions.append(f"btn = driver.find_element({locator_type}, '{locator}')")
            safe_field = (f_name or f_id).replace("'", "\\'")
            field_actions.append(f"assert btn.is_displayed(), 'Bouton non visible'")
            field_actions.append(f"print('STEP_PASS: Bouton {safe_field} verifie')")
            field_actions.append("")

    # Pour les tests spéciaux, ajouter des vérifications ciblées sans dépendre d'un fournisseur précis.
    if 'connexion' in name_lower or 'se connecter' in name_lower:
        field_actions.append("# Recherche du lien de connexion")
        field_actions.append("links = driver.find_elements(By.XPATH, \"//a[contains(text(),'connecter') or contains(text(),'Connecter') or contains(text(),'connexion')]\")")
        field_actions.append("assert len(links) > 0, 'Lien connexion non trouve'")
        field_actions.append("print('STEP_PASS: Lien connexion detecte')")

    if 'bouton' in name_lower and 'voir' in name_lower:
        field_actions.append("# Recherche du bouton Voir")
        field_actions.append("voir_btns = driver.find_elements(By.XPATH, \"//*[contains(text(),'Voir') or contains(@class,'toggle') or @type='button']\")")
        field_actions.append("assert len(voir_btns) > 0, 'Bouton Voir non trouve'")
        field_actions.append("print('STEP_PASS: Bouton Voir detecte')")

    # Ajouter le chronométrage à chaque print STEP_PASS dans les actions
    timed_actions = []
    for line in (field_actions if field_actions else ["print('STEP_PASS:0:Verification de base effectuee')"]):
        if "print('STEP_PASS:" in line:
            # Remplacer par version chronométrée
            msg = line.split("print('STEP_PASS: ")[1].rstrip("')") if "STEP_PASS: " in line else "Action"
            safe_msg = msg.replace("'", "\\'")
            timed_actions.append(f"print('STEP_PASS:' + str(int((time.time()-_t)*1000)) + ':{safe_msg}')")
            timed_actions.append("_t = time.time()")
        else:
            timed_actions.append(line)

    actions_code = "\n".join(timed_actions)

    script = (
        "from selenium import webdriver\n"
        "from selenium.webdriver.common.by import By\n"
        "from selenium.webdriver.chrome.options import Options\n"
        "import time\n\n"
        "_t = time.time()\n"
        "chrome_options = Options()\n"
        "chrome_options.add_argument('--start-maximized')\n"
        "chrome_options.add_argument('--disable-blink-features=AutomationControlled')\n\n"
        "driver = webdriver.Chrome(options=chrome_options)\n"
        "driver.implicitly_wait(10)\n\n"
        f"driver.get('{file_url}')\n"
        "time.sleep(2)\n"
        "print('STEP_PASS:' + str(int((time.time()-_t)*1000)) + ':Page chargee avec succes')\n"
        "_t = time.time()\n"
        f"print('STEP_PASS:' + str(int((time.time()-_t)*1000)) + ':Test: {safe_test_name}')\n"
        "_t = time.time()\n\n"
        f"{actions_code}\n\n"
        "print('STEP_PASS:' + str(int((time.time()-_t)*1000)) + ':Verifications terminees')\n"
        "time.sleep(1)\n"
    )
    return script


@app.post("/api/run-file-selenium")
async def run_file_selenium(request: RunFileSeleniumRequest):
    """
    Exécute les tests Selenium sur un fichier HTML.
    Si des tests sont fournis, chaque test est exécuté individuellement.
    Chrome s'ouvre en mode visible pour voir l'exécution en temps réel.
    """
    if not request.html_content or not request.html_content.strip():
        raise HTTPException(status_code=400, detail="Le contenu HTML est vide.")

    import tempfile
    import pathlib

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    html_filename = None
    temp_files_to_cleanup = []
    start_time_total = __import__('time').time()

    try:
        # 1. Décoder le HTML base64 et l'écrire dans un fichier temporaire
        html_bytes = base64.b64decode(request.html_content)
        html_fd, html_path = tempfile.mkstemp(suffix=".html", prefix="selenium_test_")
        html_filename = html_path
        with os.fdopen(html_fd, 'wb') as f:
            f.write(html_bytes)
        file_url = pathlib.Path(html_path).as_uri()
        print(f"[RunFileSelenium] Fichier HTML temporaire: {file_url}")

        # 2. Déterminer la liste de tests à exécuter
        tests_to_run = request.tests if request.tests else []
        fields = request.fields if request.fields else []

        # Si pas de tests individuels, fallback sur l'ancien comportement (1 seul script)
        if not tests_to_run:
            tests_to_run = [{"nomSenario": "Test Fichier HTML - Selenium", "description": "Exécution du script global"}]

        print(f"[RunFileSelenium] 🚀 {len(tests_to_run)} tests à exécuter individuellement")

        scenarios_results = []
        all_logs = []

        # 3. Exécuter CHAQUE test individuellement
        for idx, test in enumerate(tests_to_run, 1):
            test_name = test.get('nomSenario', test.get('name', f'Test {idx}'))
            print(f"\n[RunFileSelenium] [{idx}/{len(tests_to_run)}] Exécution: {test_name}")
            test_start = __import__('time').time()

            # Générer le script spécifique pour CE test
            if fields:
                test_script = _generate_single_test_script(test, fields, file_url)
            else:
                # Fallback: utiliser le script global avec l'URL injectée
                test_script = request.script_code
                test_script = test_script.replace("{{FILE_URL}}", file_url)
                test_script = test_script.replace("{FILE_URL}", file_url)

            # Wrapper script pour capturer les résultats
            script_file = os.path.join(tempfile.gettempdir(), f"test_{timestamp}_{idx}.py")
            screenshot_file = os.path.join(tempfile.gettempdir(), f"snap_{timestamp}_{idx}.png")
            temp_files_to_cleanup.extend([script_file, screenshot_file])
            screenshot_escaped = screenshot_file.replace("\\", "\\\\")

            indented = "\n".join(["    " + line for line in test_script.split("\n")])
            wrapped = (
                "import sys\nimport traceback\nimport time\n\ntry:\n"
                + indented
                + "\n    print('SUCCESS: Test termine avec succes.')"
                + "\nexcept AssertionError as ae:"
                + "\n    print('FAILED: Assertion echouee: ' + str(ae))"
                + "\n    traceback.print_exc()"
                + f"\n    try:\n        driver.save_screenshot('{screenshot_escaped}')\n    except Exception:\n        pass"
                + "\nexcept Exception as e:"
                + "\n    print('FAILED: Erreur: ' + str(e))"
                + "\n    traceback.print_exc()"
                + f"\n    try:\n        driver.save_screenshot('{screenshot_escaped}')\n    except Exception:\n        pass"
                + "\nfinally:"
                + "\n    try:\n        driver.quit()\n    except Exception:\n        pass"
            )

            with open(script_file, "w", encoding="utf-8") as f:
                f.write(wrapped)

            # Exécuter le script
            try:
                process = subprocess.run(
                    ["python", script_file],
                    capture_output=True, text=True, timeout=60
                )
                stdout = process.stdout or ""
                stderr = process.stderr or ""
            except subprocess.TimeoutExpired:
                stdout = ""
                stderr = "TIMEOUT: Test a dépassé 60 secondes"

            test_duration = int((__import__('time').time() - test_start) * 1000)
            status = "PASSED" if "SUCCESS: Test termine avec succes." in stdout else "FAILED"

            # Lire screenshot si dispo
            screenshot_b64 = None
            if os.path.exists(screenshot_file):
                try:
                    with open(screenshot_file, "rb") as sf:
                        screenshot_b64 = base64.b64encode(sf.read()).decode('utf-8')
                except Exception:
                    pass

            # Parser les STEP_PASS:duration:message / STEP_FAIL:duration:message du stdout
            steps = []
            for line in stdout.split("\n"):
                line = line.strip()
                if not line:
                    continue
                if line.startswith("STEP_PASS:"):
                    # Format: STEP_PASS:duration_ms:message  OU  STEP_PASS: message (ancien format)
                    parts = line.split(":", 3)  # ['STEP_PASS', 'duration', 'message'] ou ['STEP_PASS', ' message']
                    if len(parts) >= 3 and parts[1].strip().isdigit():
                        step_dur = int(parts[1].strip())
                        step_msg = parts[2].strip()
                    else:
                        step_dur = 0
                        step_msg = line.replace("STEP_PASS:", "").strip()
                    steps.append({
                        "step_text": step_msg, "action": step_msg,
                        "status": "PASSED", "duration_ms": step_dur,
                        "error_message": "", "screenshot": None
                    })
                elif line.startswith("STEP_FAIL:"):
                    parts = line.split(":", 3)
                    if len(parts) >= 3 and parts[1].strip().isdigit():
                        step_dur = int(parts[1].strip())
                        step_msg = parts[2].strip()
                    else:
                        step_dur = 0
                        step_msg = line.replace("STEP_FAIL:", "").strip()
                    steps.append({
                        "step_text": step_msg, "action": step_msg,
                        "status": "FAILED", "duration_ms": step_dur,
                        "error_message": step_msg, "screenshot": screenshot_b64
                    })
                elif "SUCCESS:" in line:
                    steps.append({
                        "step_text": line, "action": line,
                        "status": "PASSED", "duration_ms": 0,
                        "error_message": "", "screenshot": None
                    })
                elif "FAILED:" in line:
                    steps.append({
                        "step_text": line, "action": line,
                        "status": "FAILED", "duration_ms": 0,
                        "error_message": line, "screenshot": screenshot_b64
                    })

            if not steps:
                steps.append({
                    "step_text": "Exécution du test",
                    "action": "Exécution du test",
                    "status": status, "duration_ms": test_duration,
                    "error_message": stderr.strip()[:300] if status == "FAILED" else "",
                    "screenshot": screenshot_b64 if status == "FAILED" else None
                })

            scenarios_results.append({
                "scenario_name": test_name,
                "nomSenario": test_name,
                "status": status,
                "duration_ms": test_duration,
                "steps": steps,
                "screenshots": [screenshot_b64] if screenshot_b64 else [],
                "error_message": stderr.strip()[:300] if status == "FAILED" else ""
            })

            all_logs.append(f"[{idx}/{len(tests_to_run)}] {test_name}: {status} ({test_duration}ms)")
            print(f"[RunFileSelenium]   {'✓' if status == 'PASSED' else '✗'} {status} ({test_duration}ms)")

        # 4. Calculer le résumé
        total_duration = int((__import__('time').time() - start_time_total) * 1000)
        total = len(scenarios_results)
        passed = sum(1 for s in scenarios_results if s["status"] == "PASSED")
        failed = total - passed
        global_status = "PASSED" if failed == 0 else "FAILED"

        print(f"\n[RunFileSelenium] 📊 Résumé: {total} tests | {passed} réussis | {failed} échoués | {total_duration}ms")

        # 5. Générer le rapport avec TOUS les scénarios
        project_metadata = {
            "project_name": "Fichier HTML",
            "url": file_url,
            "timestamp": datetime.now().isoformat()
        }
        report = generate_rich_report(scenarios_results, project_metadata)
        pdf_base64 = report["pdf_base64"]
        print(f"[RunFileSelenium] ✓ Rapport généré avec {total} scénarios (mode: {report.get('pdf_generation_mode', 'unknown')})")

        return {
            "status": global_status,
            "logs": "\n".join(all_logs),
            "pdf_base64": pdf_base64,
            "results": {
                "total": total,
                "passed": passed,
                "failed": failed
            }
        }

    except Exception as e:
        print(f"[RunFileSelenium] ✗ Erreur: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Erreur d'exécution: {str(e)}")
    finally:
        for f in temp_files_to_cleanup:
            if f and os.path.exists(f):
                try:
                    os.remove(f)
                except:
                    pass
        if html_filename and os.path.exists(html_filename):
            try:
                os.remove(html_filename)
            except:
                pass


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8000)
