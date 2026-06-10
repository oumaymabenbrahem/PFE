"""
Self-Healing Selenium Driver Agent
===================================
Monkey-patches Selenium WebDriver's find_element / find_elements so that when a
selector (XPath, CSS, ID, name, …) no longer exists in the page, instead of
raising NoSuchElementException the agent:

    1. Detects the failure automatically.
    2. Captures a compact snapshot of the current DOM.
    3. Asks an LLM (Hugging Face Inference API) to propose a replacement selector.
    4. Retries the lookup with the new selector.
    5. Logs every healing event (old → new) for full traceability.

Usage
-----
    from self_healing_driver import enable_self_healing
    enable_self_healing(threshold=0.55)

    # After this call, ALL WebDriver instances created anywhere in the process
    # will automatically benefit from self-healing find_element / find_elements.
"""

from __future__ import annotations

import os
import re
import json
import time
import logging
import hashlib
import functools
from pathlib import Path
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

from selenium.webdriver.remote.webdriver import WebDriver
from selenium.webdriver.remote.webelement import WebElement
from selenium.webdriver.common.by import By
from selenium.common.exceptions import (
    NoSuchElementException,
    InvalidSelectorException,
    StaleElementReferenceException,
    WebDriverException,
)

logger = logging.getLogger("self_healing_driver")


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Maximum number of DOM characters sent to the LLM (to avoid token overflow)
_MAX_DOM_CHARS = 12_000

# Maximum retry attempts for self-healing per call
_MAX_HEAL_RETRIES = 2

# Cache healed selectors for the duration of the session (old_key → new_selector)
_healing_cache: Dict[str, Tuple[str, str]] = {}

# Full healing log: list of dicts with old/new selectors, timestamps, etc.
_healing_log: List[Dict[str, Any]] = []

# Minimum LLM confidence to accept a healed selector
_CONFIDENCE_THRESHOLD = 0.55

# Path to persist the healing log on disk
_LOG_DIR = Path(__file__).resolve().parent / "results"
_LOG_FILE = _LOG_DIR / "self_healing_log.json"


# ---------------------------------------------------------------------------
# LLM Integration (Hugging Face Inference API)
# ---------------------------------------------------------------------------

def _get_hf_token() -> Optional[str]:
    """Retrieve HF token from environment."""
    return os.getenv("HF_TOKEN")


def _ask_llm_for_replacement_selector(
    failed_by: str,
    failed_value: str,
    dom_snapshot: str,
    page_url: str,
) -> Optional[Tuple[str, str, float]]:
    """
    Ask the LLM to suggest a replacement selector.

    Returns
    -------
    (by_type, selector_value, confidence) or None if the LLM cannot help.
    """
    hf_token = _get_hf_token()
    if not hf_token:
        logger.warning("[SelfHealing] HF_TOKEN not set — cannot call LLM for healing.")
        return None

    from huggingface_hub import InferenceClient

    system_prompt = (
        "Tu es un expert en automatisation Selenium et en analyse DOM.\n"
        "On te fournit:\n"
        "  1. Un sélecteur Selenium qui a ÉCHOUÉ (type + valeur).\n"
        "  2. Un extrait du DOM actuel de la page.\n"
        "  3. L'URL de la page.\n\n"
        "Ta mission: trouver un sélecteur de REMPLACEMENT qui pointe vers "
        "l'élément que le sélecteur original visait.\n\n"
        "RÈGLES:\n"
        "- Réponds UNIQUEMENT en JSON valide, sans texte autour.\n"
        "- Format: {\"by\": \"...\", \"value\": \"...\", \"confidence\": 0.XX, \"reason\": \"...\"}\n"
        "- 'by' DOIT être un de: id, name, css selector, xpath, class name, "
        "tag name, link text, partial link text\n"
        "- 'confidence' est un float entre 0 et 1 indiquant ta confiance.\n"
        "- 'reason' explique brièvement pourquoi tu as choisi ce sélecteur.\n"
        "- Si tu ne peux PAS trouver de remplacement, réponds: "
        '{\"by\": null, \"value\": null, \"confidence\": 0, "reason": "..."}\n'
        "- Privilégie les sélecteurs stables: id > name > data-testid > "
        "aria-label > css > xpath.\n"
        "- Ne propose JAMAIS un sélecteur identique au sélecteur échoué.\n"
    )

    user_prompt = (
        f"SÉLECTEUR ÉCHOUÉ:\n"
        f"  Type: {failed_by}\n"
        f"  Valeur: {failed_value}\n\n"
        f"URL DE LA PAGE:\n  {page_url}\n\n"
        f"DOM ACTUEL (extrait):\n"
        f"```html\n{dom_snapshot}\n```\n\n"
        f"Propose un sélecteur de remplacement en JSON."
    )

    models = [
        "Qwen/Qwen2.5-Coder-32B-Instruct",
        "Qwen/Qwen2.5-72B-Instruct",
        "mistralai/Mixtral-8x7B-Instruct-v0.1",
    ]

    for model_name in models:
        try:
            client = InferenceClient(model_name, token=hf_token)
            response = client.chat_completion(
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                max_tokens=400,
                temperature=0.1,
            )
            raw_text = response.choices[0].message.content.strip()
            logger.info("[SelfHealing] LLM raw response: %s", raw_text[:300])
            return _parse_llm_response(raw_text)
        except Exception as e:
            logger.warning("[SelfHealing] LLM model %s failed: %s", model_name, e)
            continue

    return None


def _parse_llm_response(raw_text: str) -> Optional[Tuple[str, str, float]]:
    """Parse JSON from LLM response, handling markdown code fences."""
    # Strip markdown code fences if present
    cleaned = re.sub(r"^```(?:json)?\s*", "", raw_text, flags=re.MULTILINE)
    cleaned = re.sub(r"\s*```\s*$", "", cleaned, flags=re.MULTILINE)
    cleaned = cleaned.strip()

    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError:
        # Try to extract JSON object from the text
        match = re.search(r"\{[^{}]+\}", cleaned, re.DOTALL)
        if match:
            try:
                data = json.loads(match.group(0))
            except json.JSONDecodeError:
                logger.error("[SelfHealing] Could not parse LLM JSON response.")
                return None
        else:
            logger.error("[SelfHealing] No JSON found in LLM response.")
            return None

    by_type = data.get("by")
    value = data.get("value")
    confidence = float(data.get("confidence", 0))
    reason = data.get("reason", "")

    if not by_type or not value:
        logger.info("[SelfHealing] LLM could not find replacement: %s", reason)
        return None

    # Map human-readable by types to Selenium By constants
    by_map = {
        "id": By.ID,
        "name": By.NAME,
        "css selector": By.CSS_SELECTOR,
        "css": By.CSS_SELECTOR,
        "xpath": By.XPATH,
        "class name": By.CLASS_NAME,
        "class": By.CLASS_NAME,
        "tag name": By.TAG_NAME,
        "tag": By.TAG_NAME,
        "link text": By.LINK_TEXT,
        "partial link text": By.PARTIAL_LINK_TEXT,
    }

    selenium_by = by_map.get(by_type.lower().strip())
    if not selenium_by:
        logger.warning("[SelfHealing] Unknown 'by' type from LLM: %s", by_type)
        return None

    logger.info(
        "[SelfHealing] LLM proposed: by=%s, value=%s, confidence=%.2f, reason=%s",
        selenium_by, value, confidence, reason,
    )
    return (selenium_by, value, confidence)


# ---------------------------------------------------------------------------
# DOM Snapshot
# ---------------------------------------------------------------------------

def _capture_dom_snapshot(driver: WebDriver) -> str:
    """Capture a compact DOM snapshot focusing on interactive elements."""
    try:
        # Script simplifié et plus performant
        dom_script = """
        (function() {
            var items = document.querySelectorAll('input, button, a, textarea, select, [role="button"], [role="link"], label');
            var result = [];
            
            for (var i = 0; i < items.length && i < 150; i++) {
                var el = items[i];
                var tag = el.tagName.toLowerCase();
                
                // Vérifier la visibilité
                var style = window.getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden') continue;
                
                var attrs = [];
                var attrNames = ['id', 'name', 'class', 'type', 'placeholder', 'aria-label', 'role', 'data-testid', 'for'];
                
                for (var j = 0; j < attrNames.length; j++) {
                    var val = el.getAttribute(attrNames[j]);
                    if (val) {
                        attrs.push(attrNames[j] + '="' + val.substring(0, 70) + '"');
                    }
                }
                
                var text = (el.innerText || el.textContent || "").trim().substring(0, 50);
                var attrStr = attrs.length > 0 ? ' ' + attrs.join(' ') : '';
                
                result.push('<' + tag + attrStr + '>' + text + '</' + tag + '>');
            }
            return result.join('\\n');
        })();
        """
        dom = driver.execute_script(dom_script)
        
        if not dom or dom.strip() == "":
            # Fallback direct si vide
            dom = driver.page_source[:3000]
            
        logger.info("[SelfHealing] Captured DOM snapshot (length: %d chars)", len(dom))
        
        if len(dom) > _MAX_DOM_CHARS:
            dom = dom[:_MAX_DOM_CHARS] + "\n<!-- ... TRUNCATED ... -->"
        return dom
    except Exception as e:
        logger.warning("[SelfHealing] Failed to capture DOM snapshot: %s", e)
        return "<body>(Capture error)</body>"
    except Exception as e:
        logger.warning("[SelfHealing] Failed to capture DOM snapshot: %s", e)
        return "<body>(DOM capture failed)</body>"


# ---------------------------------------------------------------------------
# Cache Key
# ---------------------------------------------------------------------------

def _cache_key(by: str, value: str) -> str:
    """Create a deterministic cache key for a selector."""
    return hashlib.md5(f"{by}::{value}".encode()).hexdigest()


# ---------------------------------------------------------------------------
# Healing Log Persistence
# ---------------------------------------------------------------------------

def _persist_healing_log() -> None:
    """Save the accumulated healing log to disk as JSON."""
    try:
        _LOG_DIR.mkdir(parents=True, exist_ok=True)

        existing = []
        if _LOG_FILE.exists():
            try:
                existing = json.loads(_LOG_FILE.read_text(encoding="utf-8"))
            except Exception:
                existing = []

        combined = existing + _healing_log
        _LOG_FILE.write_text(
            json.dumps(combined, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception as e:
        logger.warning("[SelfHealing] Could not persist healing log: %s", e)


def get_healing_log() -> List[Dict[str, Any]]:
    """Return the in-memory healing log (useful for reports)."""
    return list(_healing_log)


def get_healing_stats() -> Dict[str, Any]:
    """Return aggregate statistics about self-healing events."""
    total = len(_healing_log)
    successes = sum(1 for entry in _healing_log if entry.get("healed"))
    failures = total - successes
    return {
        "total_attempts": total,
        "successful_heals": successes,
        "failed_heals": failures,
        "success_rate": round(successes / total * 100, 1) if total else 0,
        "cached_selectors": len(_healing_cache),
    }


# ---------------------------------------------------------------------------
# Core Self-Healing Logic
# ---------------------------------------------------------------------------

def _try_heal_and_find(
    driver: WebDriver,
    original_find: Any,
    by: str,
    value: str,
    threshold: float,
    find_multiple: bool = False,
) -> Any:
    """
    Wrapper around the original find_element / find_elements.
    If the original call fails with NoSuchElementException, triggers healing.
    """
    # --- 1. Try the original selector first ---
    try:
        result = original_find(by, value)
        # find_elements returns a list — only consider it "found" if non-empty
        if find_multiple and isinstance(result, list) and len(result) == 0:
            raise NoSuchElementException(f"No elements found with {by}={value}")
        return result
    except (NoSuchElementException, InvalidSelectorException) as original_error:
        logger.info(
            "[SelfHealing] ❌ Selector failed: by=%s, value='%s' — starting healing...",
            by, value,
        )
    except WebDriverException:
        # Other WebDriver errors (session lost, etc.) — don't try to heal
        raise

    # --- 2. Check the healing cache ---
    key = _cache_key(by, value)
    if key in _healing_cache:
        cached_by, cached_value = _healing_cache[key]
        logger.info(
            "[SelfHealing] 🔄 Cache hit: using cached selector by=%s, value='%s'",
            cached_by, cached_value,
        )
        try:
            result = original_find(cached_by, cached_value)
            if find_multiple and isinstance(result, list) and len(result) == 0:
                raise NoSuchElementException("Cached selector returned empty")
            return result
        except (NoSuchElementException, InvalidSelectorException):
            # Cached selector also broken — remove from cache and try LLM again
            del _healing_cache[key]
            logger.info("[SelfHealing] Cached selector expired, re-healing...")

    # --- 3. Capture DOM and ask LLM ---
    page_url = ""
    try:
        page_url = driver.current_url
    except Exception:
        pass

    dom_snapshot = _capture_dom_snapshot(driver)
    llm_result = _ask_llm_for_replacement_selector(by, value, dom_snapshot, page_url)

    healing_entry = {
        "timestamp": datetime.now().isoformat(),
        "page_url": page_url,
        "original_by": by,
        "original_value": value,
        "healed": False,
        "new_by": None,
        "new_value": None,
        "confidence": 0,
        "reason": "",
    }

    if not llm_result:
        healing_entry["reason"] = "LLM could not propose a replacement"
        _healing_log.append(healing_entry)
        _persist_healing_log()
        logger.error(
            "[SelfHealing] ❌ LLM could not find a replacement for by=%s, value='%s'",
            by, value,
        )
        raise NoSuchElementException(
            f"Self-healing failed: no replacement found for {by}='{value}'"
        )

    new_by, new_value, confidence = llm_result
    healing_entry["new_by"] = new_by
    healing_entry["new_value"] = new_value
    healing_entry["confidence"] = confidence

    # --- 4. Check confidence threshold ---
    if confidence < threshold:
        healing_entry["reason"] = f"Confidence {confidence:.2f} below threshold {threshold:.2f}"
        _healing_log.append(healing_entry)
        _persist_healing_log()
        logger.warning(
            "[SelfHealing] ⚠ LLM confidence (%.2f) below threshold (%.2f) — rejecting.",
            confidence, threshold,
        )
        raise NoSuchElementException(
            f"Self-healing rejected (confidence {confidence:.2f} < {threshold:.2f}) "
            f"for {by}='{value}'"
        )

    # --- 5. Try the healed selector ---
    try:
        result = original_find(new_by, new_value)
        if find_multiple and isinstance(result, list) and len(result) == 0:
            raise NoSuchElementException("Healed selector returned empty list")

        # SUCCESS 🎉
        healing_entry["healed"] = True
        healing_entry["reason"] = "Successfully healed"
        _healing_cache[key] = (new_by, new_value)
        _healing_log.append(healing_entry)
        _persist_healing_log()

        logger.info(
            "[SelfHealing] ✅ HEALED! %s='%s' → %s='%s' (confidence: %.2f)",
            by, value, new_by, new_value, confidence,
        )
        return result

    except (NoSuchElementException, InvalidSelectorException) as heal_error:
        healing_entry["reason"] = f"Healed selector also failed: {heal_error}"
        _healing_log.append(healing_entry)
        _persist_healing_log()
        logger.error(
            "[SelfHealing] ❌ Healed selector also failed: by=%s, value='%s'",
            new_by, new_value,
        )
        raise NoSuchElementException(
            f"Self-healing: replacement selector also failed. "
            f"Original: {by}='{value}', Tried: {new_by}='{new_value}'"
        ) from heal_error


# ---------------------------------------------------------------------------
# Monkey-Patching
# ---------------------------------------------------------------------------

_original_find_element = None
_original_find_elements = None
_is_enabled = False


def enable_self_healing(threshold: float = 0.55) -> None:
    """
    Activate self-healing on ALL Selenium WebDriver instances.

    Parameters
    ----------
    threshold : float
        Minimum LLM confidence required to accept a healed selector (0–1).
    """
    global _original_find_element, _original_find_elements, _is_enabled, _CONFIDENCE_THRESHOLD

    if _is_enabled:
        logger.info("[SelfHealing] Already enabled — skipping re-patch.")
        return

    _CONFIDENCE_THRESHOLD = threshold

    # Save originals
    _original_find_element = WebDriver.find_element
    _original_find_elements = WebDriver.find_elements

    @functools.wraps(_original_find_element)
    def _healed_find_element(self, by=By.ID, value=None):
        return _try_heal_and_find(
            driver=self,
            original_find=lambda b, v: _original_find_element(self, b, v),
            by=by,
            value=value,
            threshold=_CONFIDENCE_THRESHOLD,
            find_multiple=False,
        )

    @functools.wraps(_original_find_elements)
    def _healed_find_elements(self, by=By.ID, value=None):
        return _try_heal_and_find(
            driver=self,
            original_find=lambda b, v: _original_find_elements(self, b, v),
            by=by,
            value=value,
            threshold=_CONFIDENCE_THRESHOLD,
            find_multiple=True,
        )

    WebDriver.find_element = _healed_find_element
    WebDriver.find_elements = _healed_find_elements

    _is_enabled = True
    logger.info(
        "[SelfHealing] ✅ Self-healing enabled (threshold=%.2f). "
        "All WebDriver.find_element/find_elements calls are now auto-healing.",
        threshold,
    )


def disable_self_healing() -> None:
    """Restore original Selenium find_element / find_elements methods."""
    global _original_find_element, _original_find_elements, _is_enabled

    if not _is_enabled:
        return

    if _original_find_element:
        WebDriver.find_element = _original_find_element
    if _original_find_elements:
        WebDriver.find_elements = _original_find_elements

    _is_enabled = False
    logger.info("[SelfHealing] Self-healing disabled — original methods restored.")
