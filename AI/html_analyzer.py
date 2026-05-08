"""
html_analyzer.py — Analyse automatique de fichiers HTML via BeautifulSoup
=========================================================================
Parsing intelligent pour détecter :
  - Champs de formulaire (input, select, textarea, button)
  - Comportements (soumission, navigation, validation HTML5, événements JS)

Ce module est utilisé par l'endpoint POST /api/analyze-html dans main.py.
"""

from bs4 import BeautifulSoup, Comment
from typing import List, Dict, Any, Optional
import re


# ---------------------------------------------------------------------------
# Attributs JS courants qui indiquent un comportement dynamique
# ---------------------------------------------------------------------------
JS_EVENT_ATTRS = [
    "onclick", "onsubmit", "onchange", "oninput", "onfocus", "onblur",
    "onkeydown", "onkeyup", "onkeypress", "onmouseover", "onmouseout",
    "ondblclick", "onload", "onreset",
]


def analyze_html(html_content: str) -> Dict[str, Any]:
    """
    Point d'entrée principal — analyse un fichier HTML complet.

    Retourne un dict :
        {
            "fields": [ ... ],        # champs interactifs détectés
            "behaviors": [ ... ],     # comportements détectés
            "summary": { ... }        # résumé statistique
        }
    """
    soup = BeautifulSoup(html_content, "html.parser")

    fields = _extract_fields(soup)
    behaviors = _extract_behaviors(soup)

    summary = {
        "total_fields": len(fields),
        "total_behaviors": len(behaviors),
        "field_types": _count_by_key(fields, "type"),
        "behavior_types": _count_by_key(behaviors, "category"),
    }

    return {
        "fields": fields,
        "behaviors": behaviors,
        "summary": summary,
    }


# ===========================  EXTRACTION DES CHAMPS  ===========================

def _extract_fields(soup: BeautifulSoup) -> List[Dict[str, Any]]:
    """Extrait tous les champs interactifs du HTML."""
    fields: List[Dict[str, Any]] = []

    # --- input ---
    for el in soup.find_all("input"):
        input_type = (el.get("type") or "text").lower()
        if input_type == "hidden":
            continue  # ignorer les champs cachés

        field = _build_field_dict(el, "input", input_type)
        fields.append(field)

    # --- textarea ---
    for el in soup.find_all("textarea"):
        field = _build_field_dict(el, "textarea", "textarea")
        fields.append(field)

    # --- select ---
    for el in soup.find_all("select"):
        options = []
        for opt in el.find_all("option"):
            opt_text = opt.get_text(strip=True)
            opt_value = opt.get("value", "")
            if opt_text or opt_value:
                options.append({"text": opt_text, "value": opt_value})

        field = _build_field_dict(el, "select", "select")
        field["options"] = options
        fields.append(field)

    # --- button ---
    for el in soup.find_all("button"):
        btn_type = (el.get("type") or "button").lower()
        text = el.get_text(strip=True)
        field = _build_field_dict(el, "button", btn_type)
        if text:
            field["text"] = text
        fields.append(field)

    # --- input[type=submit] déjà capturé, mais on attrape aussi les <a> qui ressemblent à des boutons ---
    for el in soup.find_all("a", role="button"):
        field = _build_field_dict(el, "a", "link-button")
        field["text"] = el.get_text(strip=True)
        field["href"] = el.get("href", "")
        fields.append(field)

    return fields


def _build_field_dict(el, tag: str, field_type: str) -> Dict[str, Any]:
    """Construit un dict standardisé pour un champ HTML."""
    label_text = _find_label(el)

    field: Dict[str, Any] = {
        "tag": tag,
        "type": field_type,
        "name": el.get("name", ""),
        "id": el.get("id", ""),
        "placeholder": el.get("placeholder", ""),
        "required": el.has_attr("required"),
        "label": label_text,
        "classes": " ".join(el.get("class", [])),
    }

    # Attributs de validation HTML5
    validations: Dict[str, str] = {}
    for attr in ["pattern", "minlength", "maxlength", "min", "max", "step"]:
        val = el.get(attr)
        if val is not None:
            validations[attr] = val
    if validations:
        field["validations"] = validations

    # Valeur par défaut
    value = el.get("value", "")
    if value:
        field["value"] = value

    # disabled / readonly
    if el.has_attr("disabled"):
        field["disabled"] = True
    if el.has_attr("readonly"):
        field["readonly"] = True

    return field


def _find_label(el) -> str:
    """Cherche le <label> associé à un élément via `for` ou par parenté."""
    el_id = el.get("id")
    if el_id:
        soup_root = el.find_parent([True])  # remonte au document
        if soup_root:
            label = soup_root.find("label", attrs={"for": el_id})
            if label:
                return label.get_text(strip=True)

    # Label parent direct
    parent = el.find_parent("label")
    if parent:
        # Retirer le texte de l'élément lui-même pour ne garder que le label
        cloned = parent.get_text(strip=True)
        return cloned

    # Sibling label précédent
    prev = el.find_previous_sibling("label")
    if prev:
        return prev.get_text(strip=True)

    return ""


# ==========================  EXTRACTION DES COMPORTEMENTS  ==========================

def _extract_behaviors(soup: BeautifulSoup) -> List[Dict[str, Any]]:
    """Détecte les comportements de la page HTML."""
    behaviors: List[Dict[str, Any]] = []

    # 1. Formulaires (form submission)
    for form in soup.find_all("form"):
        action = form.get("action", "")
        method = (form.get("method") or "GET").upper()
        form_id = form.get("id", "")
        form_name = form.get("name", "")
        form_fields = len(form.find_all(["input", "textarea", "select"]))

        behaviors.append({
            "category": "form_submission",
            "icon": "bi-send",
            "label": f"Formulaire{(' #' + form_id) if form_id else (' ' + form_name) if form_name else ''}",
            "description": f"Soumission {method} → {action or '(même page)'}",
            "details": {
                "action": action,
                "method": method,
                "id": form_id,
                "name": form_name,
                "fields_count": form_fields,
            }
        })

    # 2. Liens de navigation
    links = soup.find_all("a", href=True)
    internal_links = []
    external_links = []
    for a in links:
        href = a.get("href", "")
        text = a.get_text(strip=True)
        if not href or href.startswith("#") or href.startswith("javascript:"):
            continue
        if href.startswith("http://") or href.startswith("https://"):
            external_links.append({"text": text, "href": href})
        else:
            internal_links.append({"text": text, "href": href})

    if internal_links:
        behaviors.append({
            "category": "navigation",
            "icon": "bi-signpost-2",
            "label": "Navigation interne",
            "description": f"{len(internal_links)} lien(s) interne(s) détecté(s)",
            "details": {"links": internal_links[:10]},  # limiter à 10
        })

    if external_links:
        behaviors.append({
            "category": "navigation",
            "icon": "bi-box-arrow-up-right",
            "label": "Liens externes",
            "description": f"{len(external_links)} lien(s) externe(s) détecté(s)",
            "details": {"links": external_links[:10]},
        })

    # 3. Validations HTML5
    validation_fields = []
    for el in soup.find_all(["input", "textarea", "select"]):
        rules = []
        if el.has_attr("required"):
            rules.append("required")
        if el.get("pattern"):
            rules.append(f"pattern={el['pattern']}")
        if el.get("minlength"):
            rules.append(f"minlength={el['minlength']}")
        if el.get("maxlength"):
            rules.append(f"maxlength={el['maxlength']}")
        if el.get("min"):
            rules.append(f"min={el['min']}")
        if el.get("max"):
            rules.append(f"max={el['max']}")
        input_type = (el.get("type") or "").lower()
        if input_type in ("email", "url", "number", "tel", "date"):
            rules.append(f"type={input_type}")

        if rules:
            validation_fields.append({
                "field": el.get("name") or el.get("id") or el.name,
                "rules": rules,
            })

    if validation_fields:
        behaviors.append({
            "category": "validation",
            "icon": "bi-shield-check",
            "label": "Validations HTML5",
            "description": f"{len(validation_fields)} champ(s) avec règles de validation",
            "details": {"fields": validation_fields},
        })

    # 4. Événements JavaScript en ligne
    js_elements = []
    for attr in JS_EVENT_ATTRS:
        for el in soup.find_all(attrs={attr: True}):
            js_elements.append({
                "tag": el.name,
                "event": attr,
                "handler": el[attr][:80],  # tronquer
                "id": el.get("id", ""),
            })

    if js_elements:
        behaviors.append({
            "category": "js_events",
            "icon": "bi-lightning",
            "label": "Événements JavaScript",
            "description": f"{len(js_elements)} gestionnaire(s) d'événements en ligne",
            "details": {"events": js_elements[:15]},
        })

    # 5. Scripts détectés (contenu dynamique)
    scripts = soup.find_all("script")
    script_infos = []
    for s in scripts:
        src = s.get("src", "")
        inline_len = len(s.string or "") if s.string else 0
        if src:
            script_infos.append({"type": "external", "src": src})
        elif inline_len > 0:
            # Chercher des patterns courants
            code = s.string or ""
            patterns_found = []
            if re.search(r"fetch\s*\(|XMLHttpRequest|\.ajax\s*\(|\$\.post|\$\.get", code):
                patterns_found.append("AJAX/Fetch")
            if re.search(r"addEventListener|\.on\(", code):
                patterns_found.append("Event listeners")
            if re.search(r"document\.getElementById|document\.querySelector|jQuery|\$\(", code):
                patterns_found.append("DOM manipulation")
            if re.search(r"localStorage|sessionStorage|cookie", code, re.IGNORECASE):
                patterns_found.append("Storage/Cookies")

            script_infos.append({
                "type": "inline",
                "length": inline_len,
                "patterns": patterns_found,
            })

    if script_infos:
        behaviors.append({
            "category": "dynamic_content",
            "icon": "bi-code-slash",
            "label": "Scripts détectés",
            "description": f"{len(script_infos)} script(s) ({sum(1 for s in script_infos if s['type']=='external')} externe(s), {sum(1 for s in script_infos if s['type']=='inline')} en ligne)",
            "details": {"scripts": script_infos[:10]},
        })

    return behaviors


# =============================  UTILITAIRES  =============================

def _count_by_key(items: List[Dict], key: str) -> Dict[str, int]:
    """Compte les occurrences par valeur d'une clé."""
    counts: Dict[str, int] = {}
    for item in items:
        val = item.get(key, "unknown")
        counts[val] = counts.get(val, 0) + 1
    return counts
