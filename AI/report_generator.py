"""
Report Generator - Creates rich HTML and PDF reports from test execution results
"""

import base64
import io
import html
from pathlib import Path
from typing import List, Dict, Any
from datetime import datetime
from dataclasses import asdict

try:
    from reportlab.lib.pagesizes import letter, A4
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib.units import inch
    from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_RIGHT
    from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer, PageBreak, Image as RLImage
    from reportlab.graphics.shapes import Drawing, Circle, Wedge, String
    from reportlab.lib import colors
    from reportlab.pdfgen import canvas
    HAS_REPORTLAB = True
except ImportError:
    HAS_REPORTLAB = False


def _load_report_logo_data_uri() -> str:
    """Load ST2I logo from Frontend assets and return a data URI."""
    try:
        logo_path = Path(__file__).resolve().parent.parent / "Frontend" / "src" / "assets" / "images" / "st2i-logo.png"
        if not logo_path.exists():
            return ""
        encoded = base64.b64encode(logo_path.read_bytes()).decode("ascii")
        return f"data:image/png;base64,{encoded}"
    except Exception:
        return ""


def _escape_once(value: Any) -> str:
    """Normalize potentially pre-escaped text and escape it exactly once."""
    if value is None:
        return ""
    return html.escape(html.unescape(str(value)))


def _get_report_logo_path() -> Path | None:
    """Return absolute path of ST2I logo if present."""
    logo_path = Path(__file__).resolve().parent.parent / "Frontend" / "src" / "assets" / "images" / "st2i-logo.png"
    return logo_path if logo_path.exists() else None


def generate_html_report(scenarios_results: List[Dict[str, Any]], project_metadata: Dict[str, Any]) -> str:
    """
    Generate a beautiful, well-structured, detailed HTML report in French
    that is easy to read for any user - with SVG icons, animations, and responsive design.
    """

    # Calculate summary statistics
    total_scenarios = len(scenarios_results)
    passed_scenarios = sum(1 for s in scenarios_results if s.get("status") == "PASSED")
    failed_scenarios = sum(1 for s in scenarios_results if s.get("status") in ("FAILED", "ERROR"))
    skipped_scenarios = total_scenarios - passed_scenarios - failed_scenarios
    total_duration_ms = sum(int(s.get("duration_ms", 0) or 0) for s in scenarios_results)
    total_steps = sum(len(s.get("steps", []) or []) for s in scenarios_results)
    passed_steps = sum(
        sum(1 for step in (s.get("steps", []) or []) if step.get("status") == "PASSED")
        for s in scenarios_results
    )
    failed_steps = total_steps - passed_steps
    success_rate = (passed_scenarios / total_scenarios * 100) if total_scenarios else 0
    step_rate = (passed_steps / total_steps * 100) if total_steps else 0
    failed_rate = 100 - success_rate if total_scenarios else 0
    logo_data_uri = _load_report_logo_data_uri()

    # Format timestamp
    ts = project_metadata.get("timestamp", datetime.now().isoformat())
    try:
        dt = datetime.fromisoformat(str(ts))
        formatted_ts = dt.strftime('%d/%m/%Y \u00e0 %H:%M')
    except Exception:
        formatted_ts = str(ts)

    # Colors based on success
    if success_rate >= 80:
        rate_color, rate_bg = '#16a34a', '#f0fdf4'
        rate_ring = '#86efac'
    elif success_rate >= 50:
        rate_color, rate_bg = '#ea580c', '#fff7ed'
        rate_ring = '#fdba74'
    else:
        rate_color, rate_bg = '#dc2626', '#fef2f2'
        rate_ring = '#fca5a5'

    # SVG icon templates
    svg_check = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>'
    svg_x = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>'
    svg_skip = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>'
    svg_clock = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>'
    svg_globe = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path></svg>'
    svg_camera = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"></path><circle cx="12" cy="13" r="4"></circle></svg>'
    svg_list = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"></line><line x1="8" y1="12" x2="21" y2="12"></line><line x1="8" y1="18" x2="21" y2="18"></line><line x1="3" y1="6" x2="3.01" y2="6"></line><line x1="3" y1="12" x2="3.01" y2="12"></line><line x1="3" y1="18" x2="3.01" y2="18"></line></svg>'

    html_report = f"""<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Rapport d'Ex\u00e9cution Selenium</title>
<style>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap');
*{{margin:0;padding:0;box-sizing:border-box}}
html{{scroll-behavior:smooth}}
body{{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;line-height:1.7;color:#1e293b;background:#e6e9ef;padding:20px}}
.container{{max-width:1060px;margin:0 auto}}
.report-shell{{background:#f3f4f6;border:1px solid #d7dbe2;border-radius:12px;overflow:hidden;box-shadow:0 8px 24px rgba(15,23,42,.08)}}

/* ═══ HEADER (match screenshot) ═══ */
.report-header{{background:#0f2b62;color:#f8fafc;padding:18px 26px;display:flex;justify-content:space-between;align-items:flex-start;gap:24px}}
.brand-wrap{{display:flex;align-items:flex-start;gap:12px;min-width:270px}}
.brand-logo{{width:126px;height:44px;object-fit:contain;display:block;filter:drop-shadow(0 1px 1px rgba(0,0,0,.35))}}
.brand-title{{font-size:1.45em;font-weight:800;letter-spacing:.3px;line-height:1;color:#fff}}
.brand-sub{{font-size:.9em;color:#9fb2d5;font-weight:600;margin-top:4px}}
.project-meta{{text-align:right;font-size:.88em;line-height:1.5;color:#d6deee;font-weight:600}}
.project-meta strong{{color:#ffffff;font-weight:700}}

/* ═══ GLOBAL SUMMARY PANEL ═══ */
.summary-panel{{padding:24px 26px 10px;background:#f3f4f6}}
.success-line{{display:flex;gap:22px;align-items:center;margin-bottom:14px;flex-wrap:wrap}}
.rate-ring{{width:94px;height:94px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:#f6fffa;border:4px solid {rate_color};color:{rate_color};font-size:2em;font-weight:800;flex-shrink:0}}
.success-text h2{{font-size:1.34em;font-weight:800;color:#111827;line-height:1.25}}
.success-text .muted{{font-size:.95em;color:#4b5563;font-weight:600}}

.scenario-progress{{padding-left:116px;padding-right:8px}}
.scenario-progress .labels{{display:flex;justify-content:space-between;font-size:.9em;color:#334155;font-weight:700;margin-top:6px}}
.scenario-progress .labels span:first-child{{color:#14532d}}
.scenario-progress .labels span:last-child{{color:#991b1b}}
.progress-track{{height:9px;border-radius:999px;overflow:hidden;background:#d7dce4;display:flex}}
.progress-track .ok{{height:100%;background:#1f9d6b}}
.progress-track .ko{{height:100%;background:#e24848}}

/* ═══ KPI ═══ */
@keyframes fadeUp{{from{{opacity:0;transform:translateY(15px)}}to{{opacity:1;transform:translateY(0)}}}}
@keyframes fadeIn{{from{{opacity:0}}to{{opacity:1}}}}
.kpi-row{{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;padding:16px 26px 18px;background:#f3f4f6;animation:fadeIn .5s ease-out}}
.kpi-box{{text-align:center;padding:16px 8px;border-radius:10px;border:1px solid #e5e7eb;transition:transform .2s,box-shadow .2s;background:#efefec}}
.kpi-box:hover{{transform:translateY(-3px);box-shadow:0 4px 12px rgba(0,0,0,.08)}}
.kpi-box .kpi-icon{{display:none}}
.kpi-box .kpi-num{{font-size:2em;font-weight:800;display:block;line-height:1.1}}
.kpi-box .kpi-lbl{{font-size:0.75em;color:#64748b;text-transform:uppercase;letter-spacing:.5px;font-weight:600}}
.kpi-total .kpi-num{{color:#1d4ed8}}
.kpi-passed .kpi-num{{color:#3f7f23}}
.kpi-failed .kpi-num{{color:#c13838}}
.kpi-skipped .kpi-num{{color:#334155}}
.kpi-duration .kpi-num{{color:#4c46b8}}

/* ═══ PROGRESS ═══ */
.progress-section{{padding:4px 26px 0;background:#f3f4f6}}
.progress-header{{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}}
.progress-header h3{{font-size:1.6em;font-weight:800;color:#374151;text-transform:uppercase;letter-spacing:.5px}}
.progress-bar{{height:14px;border-radius:7px;background:#e2e8f0;overflow:hidden;display:flex}}
.progress-fill{{height:100%;transition:width 1s ease-out}}
.progress-fill.green{{background:linear-gradient(90deg,#22c55e,#16a34a)}}
.progress-fill.red{{background:linear-gradient(90deg,#f87171,#dc2626)}}
.progress-fill.gray{{background:linear-gradient(90deg,#fb923c,#ea580c)}}
.progress-legend{{display:flex;gap:20px;margin-top:8px;font-size:0.82em;color:#64748b;padding-bottom:8px}}
.progress-legend span{{display:flex;align-items:center;gap:5px}}
.legend-dot{{width:10px;height:10px;border-radius:3px;display:inline-block}}

/* ═══ STEPS TABLE ═══ */
.steps-summary{{padding:8px 26px 28px;background:#f3f4f6}}
.steps-table{{width:100%;border-collapse:separate;border-spacing:0;border-radius:8px;overflow:hidden;font-size:.92em;box-shadow:none;border:1px solid #d6dae0;background:#fff}}
.steps-table th{{background:#d8d8d3;color:#3f3f46;padding:10px 12px;text-align:center;font-weight:800;font-size:.95em}}
.steps-table td{{padding:10px 12px;text-align:center;border-bottom:1px solid #e2e8f0}}
.steps-table tr:last-child td{{border-bottom:none}}
.steps-table tr:hover td{{background:#f8fafc}}

/* ═══ TOC ═══ */
.toc-section{{padding:32px 40px;background:#fff;margin-top:2px;border-top:1px solid #e5e7eb}}
.toc-section h2{{font-size:1.3em;color:#1e293b;margin-bottom:18px;display:flex;align-items:center;gap:10px}}
.toc-item{{display:flex;align-items:center;gap:14px;padding:12px 18px;border-radius:10px;margin-bottom:6px;font-size:.95em;text-decoration:none;color:#1e293b;transition:all .2s;border:1px solid transparent}}
.toc-item:hover{{background:#f1f5f9;border-color:#e2e8f0;transform:translateX(4px)}}
.toc-icon{{display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;border-radius:8px;font-weight:800;font-size:.7em;color:#fff;flex-shrink:0}}
.toc-icon.passed{{background:#16a34a}} .toc-icon.failed{{background:#dc2626}} .toc-icon.skipped{{background:#ea580c}}
.toc-right{{margin-left:auto;font-size:.8em;color:#64748b;font-weight:500}}

/* ═══ SCENARIOS ═══ */
.scenarios-section{{padding:28px 40px}}
.scenarios-section>h2{{font-size:1.3em;color:#1e293b;margin-bottom:22px;display:flex;align-items:center;gap:10px}}
.scenario-card{{background:#fff;border-radius:14px;margin-bottom:28px;box-shadow:0 2px 12px rgba(0,0,0,.05);overflow:hidden;border-left:7px solid #cbd5e1;animation:fadeUp .5s ease-out}}
.scenario-card.status-passed{{border-left-color:#16a34a}}
.scenario-card.status-failed,.scenario-card.status-error{{border-left-color:#dc2626}}
.scenario-card.status-skipped{{border-left-color:#ea580c}}

.scenario-banner{{padding:18px 24px;display:flex;justify-content:space-between;align-items:center}}
.scenario-banner.passed{{background:linear-gradient(90deg,#f0fdf4,#dcfce7)}}
.scenario-banner.failed,.scenario-banner.error{{background:linear-gradient(90deg,#fef2f2,#fee2e2)}}
.scenario-banner.skipped{{background:linear-gradient(90deg,#fff7ed,#ffedd5)}}
.scenario-banner h3{{font-size:1.1em;margin:0;font-weight:700}}
.scenario-banner .right{{display:flex;align-items:center;gap:16px}}

.status-pill{{display:inline-flex;align-items:center;gap:6px;padding:6px 16px;border-radius:999px;font-size:.78em;font-weight:700;letter-spacing:.3px;text-transform:uppercase}}
.status-pill.passed{{background:#dcfce7;color:#166534;border:1px solid #86efac}}
.status-pill.failed,.status-pill.error{{background:#fee2e2;color:#991b1b;border:1px solid #fca5a5}}
.status-pill.skipped{{background:#ffedd5;color:#9a3412;border:1px solid #fdba74}}

.scenario-info{{display:grid;grid-template-columns:repeat(4,1fr);border-bottom:1px solid #e2e8f0;font-size:.85em}}
.scenario-info .info-cell{{padding:12px 18px;border-right:1px solid #e2e8f0}}
.scenario-info .info-cell:last-child{{border-right:none}}
.scenario-info .info-cell .info-lbl{{color:#64748b;font-size:.78em;text-transform:uppercase;letter-spacing:.3px;margin-bottom:2px}}
.scenario-info .info-cell .info-val{{font-weight:700;font-size:1em}}

.scenario-body{{padding:22px}}

/* ═══ STEPS ═══ */
.step-list{{list-style:none}}
.step-row{{display:grid;grid-template-columns:38px 42px 1fr 90px;align-items:center;padding:12px 14px;border-bottom:1px solid #f1f5f9;font-size:.9em;transition:background .15s}}
.step-row:hover{{filter:brightness(.97)}}
.step-row.passed{{background:#f0fdf4;border-left:4px solid #22c55e}}
.step-row.failed{{background:#fef2f2;border-left:4px solid #ef4444}}
.step-row.skipped{{background:#f8fafc;border-left:4px solid #94a3af}}
.step-num{{text-align:center;font-weight:700;color:#94a3b8;font-size:.82em}}
.step-icon{{text-align:center;display:flex;align-items:center;justify-content:center}}
.step-icon.passed{{color:#16a34a}} .step-icon.failed{{color:#dc2626}} .step-icon.skipped{{color:#ea580c}}
.step-text{{font-family:'Inter','Segoe UI',sans-serif;font-size:.88em;word-break:break-word;line-height:1.5}}
.step-text .keyword{{font-weight:700;color:#1e40af;margin-right:4px}}
.step-text .locator{{color:#7c3aed;font-weight:600;background:#f5f3ff;padding:1px 5px;border-radius:3px;font-size:.85em}}
.step-text .value{{color:#0369a1;font-weight:500}}
.step-dur{{text-align:right;font-size:.82em;color:#64748b;font-weight:500;display:flex;align-items:center;justify-content:flex-end;gap:4px}}

.step-error-box{{margin:0 14px 8px;padding:12px 16px;background:#fef2f2;border-left:4px solid #ef4444;border-radius:0 8px 8px 0;font-size:.84em}}
.step-error-box .err-title{{font-weight:700;color:#9f1239;margin-bottom:4px;display:flex;align-items:center;gap:6px}}
.step-error-box .err-text{{color:#7f1d1d;font-family:'SF Mono',Consolas,monospace;font-size:.82em;white-space:pre-wrap;word-break:break-word}}
.step-error-box details summary{{cursor:pointer;color:#9f1239;font-weight:700;font-size:.82em;margin-top:6px}}
.step-error-box .stack-trace{{margin-top:6px;max-height:260px;overflow:auto;background:#111827;color:#e5e7eb;border-radius:8px;padding:12px;font-size:12px;line-height:1.5;white-space:pre-wrap;word-break:break-word}}

.step-screenshot-box{{margin:4px 14px 10px;padding:10px;background:#f8fafc;border-left:4px solid #94a3b8;border-radius:0 8px 8px 0}}
.step-screenshot-box img{{max-width:520px;width:100%;border-radius:8px;border:1px solid #e2e8f0;box-shadow:0 2px 8px rgba(0,0,0,.06)}}
.step-screenshot-box .ss-label{{font-size:.78em;color:#64748b;margin-top:6px;display:flex;align-items:center;gap:5px}}

/* ═══ SCENARIO ERROR ═══ */
.scenario-error{{margin:14px 0 0;border:1px solid #fecaca;background:#fff1f2;border-radius:10px;padding:14px 18px}}
.scenario-error .err-title{{font-size:.88em;font-weight:700;color:#9f1239;margin-bottom:6px;display:flex;align-items:center;gap:6px}}
.scenario-error .err-text{{color:#7f1d1d;font-family:monospace;font-size:.82em;white-space:pre-wrap;word-break:break-word}}
.scenario-error details summary{{cursor:pointer;color:#9f1239;font-weight:700;font-size:.82em;margin-top:6px}}
.scenario-error .stack-trace{{margin-top:6px;max-height:260px;overflow:auto;background:#111827;color:#e5e7eb;border-radius:8px;padding:12px;font-size:12px;line-height:1.5;white-space:pre-wrap;word-break:break-word}}

/* ═══ SCREENSHOTS ═══ */
.scenario-screenshots{{margin-top:18px;padding:16px;background:#f8fafc;border-radius:10px}}
.scenario-screenshots h4{{font-size:.95em;margin-bottom:14px;color:#334155;display:flex;align-items:center;gap:8px}}
.ss-grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:14px}}
.ss-item{{border:1px solid #e2e8f0;border-radius:10px;overflow:hidden;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.04);transition:transform .2s}}
.ss-item:hover{{transform:scale(1.02)}}
.ss-item img{{width:100%;height:auto;display:block}}
.ss-item .ss-lbl{{padding:10px;font-size:.78em;color:#64748b;text-align:center;border-top:1px solid #e2e8f0}}

/* ═══ FOOTER ═══ */
.footer{{background:#0f172a;color:#94a3b8;padding:28px 40px;text-align:center;font-size:.85em;margin-top:40px}}
.footer .footer-line{{margin-bottom:4px}}
.footer .footer-sub{{opacity:.5;font-size:.78em}}

/* ═══ RESPONSIVE ═══ */
@media(max-width:768px){{
.report-header{{padding:14px 16px;flex-direction:column;align-items:flex-start}}
.project-meta{{text-align:left}}
.summary-panel{{padding:16px}}
.scenario-progress{{padding-left:0;padding-right:0}}
.kpi-row{{grid-template-columns:repeat(3,1fr);padding:10px 16px 14px}}
.scenario-info{{grid-template-columns:repeat(2,1fr)}}
.step-row{{grid-template-columns:30px 30px 1fr 70px;font-size:.82em}}
.rate-ring{{width:80px;height:80px;font-size:1.6em}}
.scenarios-section,.toc-section,.progress-section,.steps-summary{{padding-left:20px;padding-right:20px}}
}}
@media(max-width:480px){{
.kpi-row{{grid-template-columns:repeat(2,1fr)}}
.scenario-info{{grid-template-columns:1fr}}
.step-row{{grid-template-columns:24px 24px 1fr;gap:0}}
.step-dur{{display:none}}
}}
@media print{{body{{background:#fff;padding:0}}.container{{max-width:100%}}.scenario-card{{page-break-inside:avoid;box-shadow:none;border:1px solid #ddd}}.report-header{{background:#1f3f7a!important}}}}
</style>
</head>
<body>
<div class="container">
<div class="report-shell">

<!-- ══════════════ EN-TETE RAPPORT ══════════════ -->
<div class="report-header">
    <div class="brand-wrap">
        {f'<img class="brand-logo" src="{logo_data_uri}" alt="ST2I">' if logo_data_uri else '<div><div class="brand-title">TEST2I</div><div class="brand-sub">Tests automatisés, Plus intelligents, Plus rapides</div></div>'}
        <div>
            <div class="brand-sub">Tests Automatiques Selenium</div>
        </div>
    </div>
    <div class="project-meta">
        <div>Projet : <strong>{_escape_once(project_metadata.get("project_name", "N/A"))}</strong></div>
        <div>URL : <strong>{_escape_once(project_metadata.get("url", "N/A"))}</strong></div>
        <div><strong>{_escape_once(formatted_ts)}</strong></div>
    </div>
</div>

<!-- ══════════════ RESUME GLOBAL ══════════════ -->
<div class="summary-panel">
    <div class="success-line">
        <div class="rate-ring">{success_rate:.0f}%</div>
        <div class="success-text">
            <div class="muted">Taux de réussite global</div>
            <h2>{passed_scenarios} scénarios réussis sur {total_scenarios}</h2>
        </div>
    </div>
    <div class="scenario-progress">
        <div class="progress-track">
            <div class="ok" style="width:{success_rate:.2f}%"></div>
            <div class="ko" style="width:{failed_rate:.2f}%"></div>
        </div>
        <div class="labels">
            <span>{success_rate:.0f}% succès</span>
            <span>{failed_rate:.0f}% échec</span>
        </div>
    </div>
</div>

<!-- ══════════════ INDICATEURS CL\u00c9S ══════════════ -->
<div class="kpi-row">
    <div class="kpi-box kpi-total">
        <div class="kpi-icon">{svg_list}</div>
        <span class="kpi-num">{total_scenarios}</span>
        <span class="kpi-lbl">Sc\u00e9narios</span>
    </div>
    <div class="kpi-box kpi-passed">
        <div class="kpi-icon">{svg_check}</div>
        <span class="kpi-num">{passed_scenarios}</span>
        <span class="kpi-lbl">R\u00e9ussis</span>
    </div>
    <div class="kpi-box kpi-failed">
        <div class="kpi-icon">{svg_x}</div>
        <span class="kpi-num">{failed_scenarios}</span>
        <span class="kpi-lbl">\u00c9chou\u00e9s</span>
    </div>
    <div class="kpi-box kpi-skipped">
        <div class="kpi-icon">{svg_skip}</div>
        <span class="kpi-num">{skipped_scenarios}</span>
        <span class="kpi-lbl">Ignor\u00e9s</span>
    </div>
    <div class="kpi-box kpi-duration">
        <div class="kpi-icon">{svg_clock}</div>
        <span class="kpi-num">{total_duration_ms/1000:.1f}s</span>
        <span class="kpi-lbl">Dur\u00e9e Totale</span>
    </div>
</div>

<!-- ══════════════ BARRE DE PROGRESSION ══════════════ -->
<div class="progress-section">
    <div class="progress-header">
        <h3>R\u00e9partition des R\u00e9sultats</h3>
        <span style="font-size:.82em;color:#64748b">{total_steps} \u00e9tapes au total</span>
    </div>
    <div class="progress-bar">
        <div class="progress-fill green" style="width:{(passed_scenarios/total_scenarios*100) if total_scenarios else 0}%"></div>
        <div class="progress-fill red" style="width:{(failed_scenarios/total_scenarios*100) if total_scenarios else 0}%"></div>
        <div class="progress-fill gray" style="width:{(skipped_scenarios/total_scenarios*100) if total_scenarios else 0}%"></div>
    </div>
    <div class="progress-legend">
        <span><span class="legend-dot" style="background:#16a34a"></span> R\u00e9ussi ({passed_scenarios})</span>
        <span><span class="legend-dot" style="background:#dc2626"></span> \u00c9chou\u00e9 ({failed_scenarios})</span>
        <span><span class="legend-dot" style="background:#ea580c"></span> Ignor\u00e9 ({skipped_scenarios})</span>
        <span style="margin-left:auto;font-weight:600">Taux \u00e9tapes : {step_rate:.0f}%</span>
    </div>
</div>

<!-- ══════════════ TABLEAU R\u00c9CAPITULATIF ══════════════ -->
<div class="steps-summary">
    <table class="steps-table">
        <thead><tr><th>\u00c9tapes Totales</th><th>\u00c9tapes R\u00e9ussies</th><th>\u00c9tapes \u00c9chou\u00e9es</th><th>Taux de R\u00e9ussite</th></tr></thead>
        <tbody><tr><td style="font-weight:700">{total_steps}</td><td style="color:#16a34a;font-weight:700">{passed_steps}</td><td style="color:#dc2626;font-weight:700">{failed_steps}</td><td style="font-weight:700">{step_rate:.0f}%</td></tr></tbody>
    </table>
</div>

<!-- ══════════════ TABLE DES MATI\u00c8RES ══════════════ -->
<div class="toc-section">
    <h2 style="text-align: center;">{svg_list} Table des Matières</h2>
"""

    # Table of contents
    for i, scenario in enumerate(scenarios_results, 1):
        sname = _escape_once(scenario.get("scenario_name") or scenario.get("nomSenario") or f"Sc\u00e9nario {i}")
        sstatus = (scenario.get("status") or "UNKNOWN").lower()
        s_dur = int(scenario.get("duration_ms", 0) or 0)
        s_steps = len(scenario.get("steps", []) or [])
        if sstatus == "passed":
            icon_svg, icon_cls = svg_check, "passed"
        elif sstatus in ("failed", "error"):
            icon_svg, icon_cls = svg_x, "failed"
        else:
            icon_svg, icon_cls = svg_skip, "skipped"
        html_report += f"""
    <a href="#scenario-{i}" class="toc-item">
        <span class="toc-icon {icon_cls}">{icon_svg}</span>
        <span>Sc\u00e9nario {i} : {sname}</span>
        <span class="toc-right">{s_steps} \u00e9tapes &bull; {s_dur/1000:.1f}s</span>
    </a>"""

    html_report += """
</div>

<!-- ══════════════ D\u00c9TAIL DES SC\u00c9NARIOS ══════════════ -->
<div class="scenarios-section">
    <h2>\U0001f50d D\u00e9tail des Sc\u00e9narios</h2>
"""

    # Scenarios
    for idx, scenario in enumerate(scenarios_results, 1):
        scenario_status = (scenario.get("status") or "UNKNOWN").lower()
        scenario_name = _escape_once(scenario.get("scenario_name") or scenario.get("nomSenario") or f"Sc\u00e9nario {idx}")
        duration_ms = int(scenario.get("duration_ms", 0) or 0)
        steps = scenario.get("steps", []) or []
        screenshots = scenario.get("screenshots", []) or []
        error_msg = scenario.get("error_message", "")

        passed_in_scenario = sum(1 for s in steps if s.get("status") == "PASSED")
        failed_in_scenario = len(steps) - passed_in_scenario

        if scenario_status == "passed":
            pill_icon = svg_check
        elif scenario_status in ("failed", "error"):
            pill_icon = svg_x
        else:
            pill_icon = svg_skip

        html_report += f"""
    <div class="scenario-card status-{scenario_status}" id="scenario-{idx}">
        <div class="scenario-banner {scenario_status}">
            <h3>Sc\u00e9nario {idx} : {scenario_name}</h3>
            <div class="right">
                <span class="status-pill {scenario_status}">{pill_icon} {scenario_status.upper()}</span>
                <span style="font-size:.85em;color:#64748b;font-weight:600;display:flex;align-items:center;gap:4px">{svg_clock} {duration_ms/1000:.1f}s</span>
            </div>
        </div>
        <div class="scenario-info">
            <div class="info-cell"><div class="info-lbl">Dur\u00e9e</div><div class="info-val">{duration_ms} ms ({duration_ms/1000:.1f}s)</div></div>
            <div class="info-cell"><div class="info-lbl">\u00c9tapes</div><div class="info-val">{len(steps)}</div></div>
            <div class="info-cell"><div class="info-lbl">R\u00e9ussies</div><div class="info-val" style="color:#16a34a">{passed_in_scenario}</div></div>
            <div class="info-cell"><div class="info-lbl">\u00c9chou\u00e9es</div><div class="info-val" style="color:#dc2626">{failed_in_scenario}</div></div>
        </div>
        <div class="scenario-body">
            <ul class="step-list">
"""

        # Steps
        for step_idx, step in enumerate(steps, 1):
            step_status = (step.get("status") or "UNKNOWN").lower()
            step_text_raw = html.unescape(str(step.get("step_text") or step.get("action") or ""))
            step_text_escaped = html.escape(step_text_raw)
            step_duration = int(step.get("duration_ms", 0) or 0)
            error = step.get("error_message", "")
            step_screenshot = step.get("screenshot") or step.get("screenshot_base64")

            if step_status == "passed":
                step_icon_svg = svg_check
            elif step_status == "failed":
                step_icon_svg = svg_x
            else:
                step_icon_svg = svg_skip

            # Split keyword
            parts = step_text_escaped.split(" ", 1)
            keyword = parts[0] if parts else ""
            rest = parts[1] if len(parts) > 1 else ""

            html_report += f"""
                <li class="step-row {step_status}">
                    <span class="step-num">{step_idx}</span>
                    <span class="step-icon {step_status}">{step_icon_svg}</span>
                    <span class="step-text"><span class="keyword">{keyword}</span> {rest}</span>
                    <span class="step-dur">{svg_clock} {step_duration} ms</span>
                </li>
"""

            # Error
            if error:
                error_str = str(error)
                error_escaped = _escape_once(error_str)
                has_stack = ("\n" in error_str) or ("traceback" in error_str.lower())
                if has_stack:
                    preview = _escape_once(error_str.splitlines()[0])
                    html_report += f"""
                <li class="step-error-box">
                    <div class="err-title">{svg_x} Erreur de l'\u00e9tape</div>
                    <div class="err-text">{preview}</div>
                    <details><summary>\U0001f50d Afficher la stack trace compl\u00e8te</summary><pre class="stack-trace">{error_escaped}</pre></details>
                </li>
"""
                else:
                    html_report += f"""
                <li class="step-error-box">
                    <div class="err-title">{svg_x} Erreur de l'\u00e9tape</div>
                    <div class="err-text">{error_escaped}</div>
                </li>
"""

            # Screenshot
            if step_screenshot:
                html_report += f"""
                <li class="step-screenshot-box">
                    <img src="data:image/png;base64,{step_screenshot}" alt="Capture d'\u00e9cran">
                    <div class="ss-label">{svg_camera} Capture d'\u00e9cran &mdash; {step_status.upper()}</div>
                </li>
"""

        html_report += """
            </ul>
"""

        # Scenario error
        if error_msg:
            error_msg_escaped = _escape_once(error_msg)
            has_stack = ("\n" in str(error_msg)) or ("traceback" in str(error_msg).lower())
            if has_stack:
                preview = _escape_once(str(error_msg).splitlines()[0])
                html_report += f"""
            <div class="scenario-error">
                <div class="err-title">{svg_x} Erreur du Sc\u00e9nario</div>
                <div class="err-text">{preview}</div>
                <details><summary>\U0001f50d Afficher la stack trace compl\u00e8te</summary><pre class="stack-trace">{error_msg_escaped}</pre></details>
            </div>
"""
            else:
                html_report += f"""
            <div class="scenario-error">
                <div class="err-title">{svg_x} Erreur du Sc\u00e9nario</div>
                <div class="err-text">{error_msg_escaped}</div>
            </div>
"""

        # Scenario screenshots
        if screenshots:
            html_report += f"""
            <div class="scenario-screenshots">
                <h4>{svg_camera} Captures d'\u00c9cran du Sc\u00e9nario</h4>
                <div class="ss-grid">
"""
            for idx_ss, screenshot_b64 in enumerate(screenshots, 1):
                html_report += f"""
                    <div class="ss-item">
                        <img src="data:image/png;base64,{screenshot_b64}" alt="Capture {idx_ss}">
                        <div class="ss-lbl">Capture {idx_ss}</div>
                    </div>
"""
            html_report += """
                </div>
            </div>
"""

        html_report += """
        </div>
    </div>
"""

    # Footer
    html_report += f"""
</div>

<div class="footer">
    <div class="footer-line">\U0001f680 Rapport g\u00e9n\u00e9r\u00e9 automatiquement par <strong>PFE Intelligent Test Generator</strong></div>
    <div class="footer-sub">{_escape_once(formatted_ts)} &bull; Tous droits r\u00e9serv\u00e9s &copy; 2026</div>
</div>

</div>
</div>
</body>
</html>
"""

    return html_report


def generate_pdf_from_html(html_content: str) -> str:
    """
    Convert HTML to PDF and return as base64-encoded string
    Falls back to basic PDF if reportlab not available
    """

    if not HAS_REPORTLAB:
        # Fallback: return HTML as base64 (browser can display)
        return base64.b64encode(html_content.encode()).decode()

    try:
        # Try HTML -> PDF conversion first
        from io import BytesIO
        from xhtml2pdf import xhtml2pdf

        # Create PDF buffer
        pdf_buffer = BytesIO()

        # Convert HTML to PDF
        xhtml2pdf.pisaDocument(
            BytesIO(html_content.encode('utf-8')),
            pdf_buffer
        )

        pdf_bytes = pdf_buffer.getvalue()
        return base64.b64encode(pdf_bytes).decode()

    except Exception as e:
        print(f"⚠ Warning: PDF generation with reportlab failed: {e}")
        print("  HTML conversion unavailable, fallback PDF will be used.")

        # Keep old behavior for compatibility with callers of this function.
        try:
            pdf_buffer = io.BytesIO()
            doc = SimpleDocTemplate(pdf_buffer, pagesize=A4)
            story = []

            styles = getSampleStyleSheet()
            title_style = ParagraphStyle(
                'CustomTitle',
                parent=styles['Heading1'],
                fontSize=24,
                textColor=colors.HexColor('#667eea'),
                spaceAfter=30,
                alignment=TA_CENTER
            )

            story.append(Paragraph("Test Execution Report", title_style))
            story.append(Spacer(1, 0.5 * inch))
            story.append(Paragraph("Report generated by PFE Intelligent Test Generator", styles['Normal']))
            story.append(Spacer(1, 0.3 * inch))

            doc.build(story)
            pdf_bytes = pdf_buffer.getvalue()
            return base64.b64encode(pdf_bytes).decode()

        except Exception as e2:
            print(f"✗ PDF generation failed completely: {e2}")
            # Return empty PDF as base64
            return ""


def _safe_text(value: Any) -> str:
    """Escape text for ReportLab Paragraph usage."""
    return _escape_once(value)


def _build_detailed_pdf_report(scenarios_results: List[Dict[str, Any]], project_metadata: Dict[str, Any]) -> str:
    """Generate a professional, well-structured PDF report using ReportLab."""
    if not HAS_REPORTLAB:
        return ""

    pdf_buffer = io.BytesIO()
    page_w, page_h = A4
    doc = SimpleDocTemplate(
        pdf_buffer,
        pagesize=A4,
        rightMargin=0,
        leftMargin=0,
        topMargin=0,
        bottomMargin=0
    )
    content_w = page_w - doc.leftMargin - doc.rightMargin
    story = []
    styles = getSampleStyleSheet()

    # ── Register Arial Unicode fonts for French accents ──────────────
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.pdfbase.pdfmetrics import registerFontFamily
    import os as _os
    _fonts_dir = 'C:/Windows/Fonts'
    _arial_path = _os.path.join(_fonts_dir, 'arial.ttf')
    if _os.path.exists(_arial_path):
        pdfmetrics.registerFont(TTFont('Arial', _os.path.join(_fonts_dir, 'arial.ttf')))
        pdfmetrics.registerFont(TTFont('Arial-Bold', _os.path.join(_fonts_dir, 'arialbd.ttf')))
        pdfmetrics.registerFont(TTFont('Arial-Italic', _os.path.join(_fonts_dir, 'ariali.ttf')))
        pdfmetrics.registerFont(TTFont('Arial-BoldItalic', _os.path.join(_fonts_dir, 'arialbi.ttf')))
        registerFontFamily('Arial', normal='Arial', bold='Arial-Bold', italic='Arial-Italic', boldItalic='Arial-BoldItalic')
        _FONT = 'Arial'
        _FONT_B = 'Arial-Bold'
        _FONT_I = 'Arial-Italic'
        _FONT_BI = 'Arial-BoldItalic'
    else:
        _FONT = 'Helvetica'
        _FONT_B = 'Helvetica-Bold'
        _FONT_I = 'Helvetica-Oblique'
        _FONT_BI = 'Helvetica-BoldOblique'

    # ── Custom Styles ──────────────────────────────────────────────────
    cover_title = ParagraphStyle('CoverTitle', parent=styles['Title'],
        fontName=_FONT_B, fontSize=28, textColor=colors.white, alignment=TA_CENTER, spaceAfter=12, leading=34)
    cover_sub = ParagraphStyle('CoverSub', parent=styles['Normal'],
        fontName=_FONT, fontSize=14, textColor=colors.HexColor('#c7d2fe'), alignment=TA_CENTER, spaceAfter=6)
    cover_meta = ParagraphStyle('CoverMeta', parent=styles['Normal'],
        fontName=_FONT, fontSize=11, textColor=colors.HexColor('#e0e7ff'), alignment=TA_CENTER, leading=16)

    h1 = ParagraphStyle('H1', parent=styles['Heading1'],
        fontName=_FONT_B, fontSize=18, textColor=colors.HexColor('#1e293b'), spaceBefore=18, spaceAfter=10,
        borderPadding=(0, 0, 4, 0))
    h2 = ParagraphStyle('H2', parent=styles['Heading2'],
        fontName=_FONT_B, fontSize=14, textColor=colors.HexColor('#1e40af'), spaceBefore=14, spaceAfter=8)
    h3 = ParagraphStyle('H3', parent=styles['Heading3'],
        fontName=_FONT_B, fontSize=12, textColor=colors.HexColor('#334155'), spaceBefore=10, spaceAfter=6)

    body = ParagraphStyle('Body', parent=styles['Normal'],
        fontName=_FONT, fontSize=10, textColor=colors.HexColor('#1e293b'), leading=14, spaceAfter=4)
    body_small = ParagraphStyle('BodySmall', parent=body, fontName=_FONT, fontSize=9, leading=12)
    body_bold = ParagraphStyle('BodyBold', parent=body,
        fontName=_FONT_B, textColor=colors.HexColor('#0f172a'))
    error_style = ParagraphStyle('Error', parent=body,
        textColor=colors.HexColor('#991b1b'), backColor=colors.HexColor('#fef2f2'),
        borderPadding=6, fontSize=9, fontName=_FONT, leading=12)
    passed_style = ParagraphStyle('Passed', parent=body,
        textColor=colors.HexColor('#166534'), fontName=_FONT_B)
    failed_style = ParagraphStyle('Failed', parent=body,
        textColor=colors.HexColor('#991b1b'), fontName=_FONT_B)
    skipped_style = ParagraphStyle('Skipped', parent=body,
        textColor=colors.HexColor('#9a3412'), fontName=_FONT_B)
    toc_style = ParagraphStyle('TOC', parent=body, fontName=_FONT, fontSize=11, leading=18,
        textColor=colors.HexColor('#1e40af'), leftIndent=20)
    footer_style = ParagraphStyle('Footer', parent=body,
        fontName=_FONT, fontSize=8, textColor=colors.HexColor('#94a3b8'), alignment=TA_CENTER)

    # ── Helpers ────────────────────────────────────────────────────────
    def status_color(status: str):
        s = (status or "").upper()
        if s == "PASSED": return colors.HexColor('#16a34a')
        if s in ("FAILED", "ERROR"): return colors.HexColor('#dc2626')
        return colors.HexColor('#ea580c')

    def status_bg(status: str):
        s = (status or "").upper()
        if s == "PASSED": return colors.HexColor('#f0fdf4')
        if s in ("FAILED", "ERROR"): return colors.HexColor('#fef2f2')
        return colors.HexColor('#fff7ed')

    def status_icon(status: str):
        s = (status or "").upper()
        if s == "PASSED": return "PASS"
        if s in ("FAILED", "ERROR"): return "FAIL"
        return "SKIP"

    def _colored_bar(ratio, width=460):
        """Draw a colored progress bar as a table."""
        passed_w = int(width * ratio)
        failed_w = width - passed_w
        if failed_w < 1: failed_w = 1
        bar_data = [['', '']]
        bar = Table(bar_data, colWidths=[passed_w, failed_w])
        bar.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (0, 0), colors.HexColor('#22c55e')),
            ('BACKGROUND', (1, 0), (1, 0), colors.HexColor('#ef4444') if ratio < 1 else colors.HexColor('#22c55e')),
            ('LINEBELOW', (0, 0), (-1, 0), 0, colors.white),
            ('LINEABOVE', (0, 0), (-1, 0), 0, colors.white),
            ('TOPPADDING', (0, 0), (-1, -1), 0),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 0),
            ('LEFTPADDING', (0, 0), (-1, -1), 0),
            ('RIGHTPADDING', (0, 0), (-1, -1), 0),
        ]))
        return bar

    # ── Statistics ──────────────────────────────────────────────────────
    total_scenarios = len(scenarios_results)
    passed = sum(1 for s in scenarios_results if s.get("status") == "PASSED")
    failed = sum(1 for s in scenarios_results if s.get("status") in ("FAILED", "ERROR"))
    skipped = total_scenarios - passed - failed
    total_duration_ms = sum(int(s.get("duration_ms", 0) or 0) for s in scenarios_results)
    total_steps = sum(len(s.get("steps", []) or []) for s in scenarios_results)
    passed_steps = sum(
        sum(1 for step in (s.get("steps", []) or []) if step.get("status") == "PASSED")
        for s in scenarios_results
    )
    failed_steps = total_steps - passed_steps
    success_rate = (passed / total_scenarios * 100) if total_scenarios else 0

    # ═══════════════════════════════════════════════════════════════════
    # PAGE 1: COVER PAGE (style aligned with product screenshot)
    # ═══════════════════════════════════════════════════════════════════
    ts = project_metadata.get('timestamp', datetime.now().isoformat())
    try:
        dt = datetime.fromisoformat(str(ts))
        formatted_ts = dt.strftime('%d/%m/%Y \u00e0 %H:%M')
    except Exception:
        formatted_ts = str(ts)

    header_left = []
    logo_path = _get_report_logo_path()
    if logo_path:
        try:
            header_left.append(RLImage(str(logo_path), width=95, height=30))
            header_left.append(Spacer(1, 4))
        except Exception:
            header_left.append(Paragraph("TEST2I", ParagraphStyle('CoverBrandTitle', parent=body, fontName=_FONT_B, fontSize=22, textColor=colors.white)))
    else:
        header_left.append(Paragraph("TEST2I", ParagraphStyle('CoverBrandTitle', parent=body, fontName=_FONT_B, fontSize=22, textColor=colors.white)))

    header_left.append(Paragraph("Tests Automatiques Selenium", ParagraphStyle('CoverBrandSub', parent=body, fontName=_FONT, fontSize=10, textColor=colors.HexColor('#c8d4ef'))))

    header_right = [
        Paragraph(f"<b>Projet :</b> {_safe_text(project_metadata.get('project_name', 'N/A'))}", ParagraphStyle('CoverMetaRight', parent=body, fontName=_FONT_B, fontSize=10, textColor=colors.HexColor('#eef2ff'), alignment=TA_RIGHT)),
        Paragraph(f"<b>URL :</b> {_safe_text(project_metadata.get('url', 'N/A'))}", ParagraphStyle('CoverMetaRight2', parent=body, fontName=_FONT_B, fontSize=10, textColor=colors.HexColor('#eef2ff'), alignment=TA_RIGHT)),
        Paragraph(f"<b>{formatted_ts}</b>", ParagraphStyle('CoverMetaRight3', parent=body, fontName=_FONT_B, fontSize=10, textColor=colors.HexColor('#eef2ff'), alignment=TA_RIGHT)),
    ]

    header_left_w = content_w * 0.40
    header_right_w = content_w - header_left_w
    header_tbl = Table([[header_left, header_right]], colWidths=[header_left_w, header_right_w], rowHeights=[64])
    header_tbl.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#0f2b62')),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('LEFTPADDING', (0, 0), (0, 0), 14),
        ('RIGHTPADDING', (1, 0), (1, 0), 14),
        ('TOPPADDING', (0, 0), (-1, -1), 8),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
    ]))
    story.append(header_tbl)

    failed_or_error = failed
    global_status = "Succès complet" if failed_or_error == 0 else ("Échec partiel" if passed > 0 else "Échec")
    result_color = '#16a34a' if failed_or_error == 0 else '#dc2626'
    alert_left = Paragraph(
        f"<font color='white'><b>●</b></font>  <b>Résultat :</b> <font color='white'>{global_status}</font> — {failed_or_error} scénario(s) échoué(s)",
        ParagraphStyle('AlertLeft', parent=body, fontName=_FONT_B, fontSize=10, textColor=colors.white)
    )
    alert_right = Paragraph(
        f"<font color='white'>{passed} réussi(s) · {failed} échoué(s) · {skipped} ignoré(s)</font>",
        ParagraphStyle('AlertRight', parent=body_small, fontName=_FONT, fontSize=9, textColor=colors.white, alignment=TA_RIGHT)
    )
    alert_left_w = content_w * 0.68
    alert_right_w = content_w - alert_left_w
    alert_tbl = Table([[alert_left, alert_right]], colWidths=[alert_left_w, alert_right_w], rowHeights=[22])
    alert_tbl.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#1a3a7a')),
        ('LINEBELOW', (0, 0), (-1, 0), 0, colors.HexColor('#1a3a7a')),
        ('LEFTPADDING', (0, 0), (-1, -1), 8),
        ('RIGHTPADDING', (0, 0), (-1, -1), 8),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
    ]))
    story.append(alert_tbl)
    story.append(Spacer(1, 12))

    rate_color = '#16a34a' if success_rate >= 80 else ('#ea580c' if success_rate >= 50 else '#dc2626')
    donut = Drawing(78, 78)
    donut.add(Circle(39, 39, 30, strokeColor=colors.HexColor('#f3caca'), strokeWidth=4, fillColor=None))
    angle = max(0, min(360, 360 * (success_rate / 100.0)))
    if angle > 0:
        donut.add(Wedge(39, 39, 30, 90 - angle, 90, fillColor=colors.HexColor(rate_color), strokeColor=colors.HexColor(rate_color)))
        donut.add(Circle(39, 39, 24, fillColor=colors.HexColor('#f2f2ee'), strokeColor=colors.HexColor('#f2f2ee')))
    donut.add(String(39, 33, f"{success_rate:.0f}%", textAnchor='middle', fontName=_FONT_B, fontSize=16, fillColor=colors.HexColor(rate_color)))

    summary_left_w = 92
    summary_right_w = content_w - summary_left_w
    scen_bar_total_w = max(160, int(summary_right_w - 30))
    scen_pass_w = int(scen_bar_total_w * ((passed / total_scenarios) if total_scenarios else 0))
    scen_fail_w = scen_bar_total_w - scen_pass_w
    if scen_pass_w < 1:
        scen_pass_w = 1
    if scen_fail_w < 1:
        scen_fail_w = 1

    scenario_bar = Table([['', '']], colWidths=[scen_pass_w, scen_fail_w], rowHeights=[6])
    scenario_bar.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (0, 0), colors.HexColor('#22a874')),
        ('BACKGROUND', (1, 0), (1, 0), colors.HexColor('#e44d4d')),
        ('TOPPADDING', (0, 0), (-1, -1), 0),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 0),
        ('LEFTPADDING', (0, 0), (-1, -1), 0),
        ('RIGHTPADDING', (0, 0), (-1, -1), 0),
    ]))

    summary_right = [
        Paragraph(f"<b>{passed} scénarios réussis sur {total_scenarios}</b>", ParagraphStyle('SumMain', parent=body, fontName=_FONT_B, fontSize=13, textColor=colors.HexColor('#111827'))),
        Paragraph("Taux de réussite global — attention requise" if failed > 0 else "Taux de réussite global — excellent", ParagraphStyle('SumSub', parent=body, fontName=_FONT, fontSize=10, textColor=colors.HexColor('#374151'))),
        Spacer(1, 3),
        scenario_bar,
        Spacer(1, 3),
        Paragraph(
            f"<font color='#1f9d64'><b>{success_rate:.0f}% succès ({passed} scénarios)</b></font>",
            ParagraphStyle('SumLblA', parent=body_small, fontName=_FONT_B, fontSize=8)
        ),
        Paragraph(
            f"<font color='#e24848'><b>{100-success_rate:.0f}% échec ({failed} scénarios)</b></font>",
            ParagraphStyle('SumLblB', parent=body_small, fontName=_FONT_B, fontSize=8, alignment=TA_RIGHT)
        ),
    ]

    summary_card = Table([[donut, summary_right]], colWidths=[summary_left_w, summary_right_w], rowHeights=[78])
    summary_card.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#f2f2ee')),
        ('BOX', (0, 0), (-1, -1), 0.8, colors.HexColor('#d6d6d6')),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('LEFTPADDING', (0, 0), (-1, -1), 10),
        ('RIGHTPADDING', (0, 0), (-1, -1), 10),
        ('TOPPADDING', (0, 0), (-1, -1), 6),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
    ]))
    story.append(summary_card)
    story.append(Spacer(1, 12))

    # Summary boxes on cover page
    box_data = [
        [Paragraph(f"<b>{total_scenarios}</b>", ParagraphStyle('BoxNum', parent=body, fontSize=20, alignment=TA_CENTER, textColor=colors.HexColor('#1e5db0'))),
         Paragraph(f"<b>{passed}</b>", ParagraphStyle('BoxNum', parent=body, fontSize=20, alignment=TA_CENTER, textColor=colors.HexColor('#3f7f23'))),
         Paragraph(f"<b>{failed}</b>", ParagraphStyle('BoxNum', parent=body, fontSize=20, alignment=TA_CENTER, textColor=colors.HexColor('#c13838'))),
         Paragraph(f"<b>{skipped}</b>", ParagraphStyle('BoxNum', parent=body, fontSize=20, alignment=TA_CENTER, textColor=colors.HexColor('#3f3f46'))),
         Paragraph(f"<b>{total_duration_ms/1000:.1f}s</b>", ParagraphStyle('BoxNum', parent=body, fontSize=20, alignment=TA_CENTER, textColor=colors.HexColor('#4c46b8')))],
        [Paragraph("Scenarios", ParagraphStyle('BoxLbl', parent=body_small, alignment=TA_CENTER, textColor=colors.HexColor('#64748b'))),
         Paragraph("R\u00e9ussis", ParagraphStyle('BoxLbl', parent=body_small, alignment=TA_CENTER, textColor=colors.HexColor('#64748b'))),
         Paragraph("\u00c9chou\u00e9s", ParagraphStyle('BoxLbl', parent=body_small, alignment=TA_CENTER, textColor=colors.HexColor('#64748b'))),
         Paragraph("Ignor\u00e9s", ParagraphStyle('BoxLbl', parent=body_small, alignment=TA_CENTER, textColor=colors.HexColor('#64748b'))),
         Paragraph("Dur\u00e9e", ParagraphStyle('BoxLbl', parent=body_small, alignment=TA_CENTER, textColor=colors.HexColor('#64748b')))]
    ]
    kpi_col = content_w / 5
    box_table = Table(box_data, colWidths=[kpi_col, kpi_col, kpi_col, kpi_col, kpi_col])
    box_table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#f6f6f6')),
        ('BOX', (0, 0), (0, -1), 0.7, colors.HexColor('#d0d4db')),
        ('BOX', (1, 0), (1, -1), 0.9, colors.HexColor('#16a34a')),
        ('BOX', (2, 0), (2, -1), 0.9, colors.HexColor('#ef4444')),
        ('BOX', (3, 0), (3, -1), 0.7, colors.HexColor('#d0d4db')),
        ('BOX', (4, 0), (4, -1), 0.7, colors.HexColor('#d0d4db')),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('TOPPADDING', (0, 0), (-1, 0), 12),
        ('BOTTOMPADDING', (0, 0), (-1, 0), 4),
        ('TOPPADDING', (0, 1), (-1, 1), 2),
        ('BOTTOMPADDING', (0, 1), (-1, 1), 10),
    ]))
    story.append(box_table)
    story.append(Spacer(1, 16))

    # Progress bar
    story.append(Paragraph("<b>RÉPARTITION DES ÉTAPES</b>", ParagraphStyle('StepsTitle', parent=body, fontName=_FONT_B, fontSize=10, textColor=colors.HexColor('#4b5563'))))
    story.append(Spacer(1, 4))
    steps_ratio = (passed_steps / total_steps) if total_steps else 0
    steps_ok_w = int(content_w * steps_ratio)
    steps_ko_w = int(content_w - steps_ok_w)
    if steps_ok_w < 1:
        steps_ok_w = 1
    if steps_ko_w < 1:
        steps_ko_w = 1
    step_bar = Table([[Paragraph(f"<font color='white'><b>{passed_steps} OK</b></font>", ParagraphStyle('SegOk', parent=body_small, alignment=TA_CENTER, fontName=_FONT_B, fontSize=8)),
                       Paragraph(f"<font color='white'><b>{failed_steps} KO</b></font>", ParagraphStyle('SegKo', parent=body_small, alignment=TA_CENTER, fontName=_FONT_B, fontSize=8))]],
                     colWidths=[steps_ok_w, steps_ko_w], rowHeights=[16])
    step_bar.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (0, 0), colors.HexColor('#1fa77a')),
        ('BACKGROUND', (1, 0), (1, 0), colors.HexColor('#e24848')),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('TOPPADDING', (0, 0), (-1, -1), 0),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 0),
        ('LEFTPADDING', (0, 0), (-1, -1), 0),
        ('RIGHTPADDING', (0, 0), (-1, -1), 0),
    ]))
    story.append(step_bar)
    story.append(Spacer(1, 4))
    story.append(Paragraph(
        f"{total_steps} étapes totales                                  Taux étapes: <b>{(passed_steps/total_steps*100):.0f}%</b>" if total_steps else "0 étape totale",
        ParagraphStyle('StepsLegend', parent=body_small, fontName=_FONT, fontSize=9, textColor=colors.HexColor('#374151'))))

    # Steps summary
    story.append(Spacer(1, 12))
    num_cell_style = ParagraphStyle(
        'StepCardNum',
        parent=body,
        fontName=_FONT_B,
        alignment=TA_CENTER,
        fontSize=17,
        leading=18,
        spaceBefore=0,
        spaceAfter=0,
    )
    lbl_cell_style = ParagraphStyle(
        'StepCardLabel',
        parent=body_small,
        alignment=TA_CENTER,
        fontSize=9,
        leading=10,
        textColor=colors.HexColor('#374151'),
        spaceBefore=0,
        spaceAfter=0,
    )
    steps_cards = [
        [Paragraph(f"<font color='#111827'><b>{total_steps}</b></font>", num_cell_style),
         Paragraph(f"<font color='#3f7f23'><b>{passed_steps}</b></font>", num_cell_style),
         Paragraph(f"<font color='#c13838'><b>{failed_steps}</b></font>", num_cell_style),
         Paragraph(f"<font color='#1e5db0'><b>{(passed_steps/total_steps*100):.0f}%</b></font>" if total_steps else "<font color='#1e5db0'><b>0%</b></font>", num_cell_style)],
        [Paragraph("Étapes totales", lbl_cell_style),
         Paragraph("Étapes réussies", lbl_cell_style),
         Paragraph("Étapes échouées", lbl_cell_style),
         Paragraph("Taux étapes", lbl_cell_style)]
    ]
    step_card_col = content_w / 4
    steps_cards_tbl = Table(
        steps_cards,
        colWidths=[step_card_col, step_card_col, step_card_col, step_card_col],
        rowHeights=[34, 26]
    )
    steps_cards_tbl.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#f6f6f6')),
        ('BOX', (0, 0), (-1, -1), 0.7, colors.HexColor('#d1d5db')),
        ('INNERGRID', (0, 0), (-1, -1), 0.7, colors.HexColor('#d1d5db')),
        ('TOPPADDING', (0, 0), (-1, 0), 5),
        ('BOTTOMPADDING', (0, 0), (-1, 0), 1),
        ('TOPPADDING', (0, 1), (-1, 1), 0),
        ('BOTTOMPADDING', (0, 1), (-1, 1), 4),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
    ]))
    story.append(steps_cards_tbl)

    story.append(Spacer(1, 10))
    footer_left_w = content_w * 0.5
    footer_right_w = content_w - footer_left_w
    footer_line = Table([[Paragraph("TEST2I  Automation Suite", ParagraphStyle('FootL', parent=body_small, fontName=_FONT_B, fontSize=8, textColor=colors.HexColor('#374151'))),
                          Paragraph(f"Rapport généré le {formatted_ts}<br/>ID : {_safe_text(project_metadata.get('project_name', 'N/A'))}", ParagraphStyle('FootR', parent=body_small, alignment=TA_RIGHT, fontSize=8, textColor=colors.HexColor('#4b5563')))]],
                        colWidths=[footer_left_w, footer_right_w])
    footer_line.setStyle(TableStyle([
        ('LINEABOVE', (0, 0), (-1, 0), 0.6, colors.HexColor('#d1d5db')),
        ('TOPPADDING', (0, 0), (-1, -1), 6),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 0),
    ]))
    story.append(footer_line)

    story.append(PageBreak())

    # ═══════════════════════════════════════════════════════════════════
    # PAGE 2: TABLE OF CONTENTS
    # ═══════════════════════════════════════════════════════════════════
    story.append(Paragraph("Table des Mati\u00e8res", h1))
    story.append(Spacer(1, 8))

    for i, scenario in enumerate(scenarios_results, 1):
        sname = _safe_text(scenario.get("scenario_name") or scenario.get("nomSenario") or f"Scenario {i}")
        sstatus = (scenario.get("status") or "UNKNOWN").upper()
        icon = status_icon(sstatus)
        sc = '#16a34a' if sstatus == 'PASSED' else ('#dc2626' if sstatus in ('FAILED', 'ERROR') else '#ea580c')
        story.append(Paragraph(
            f'<font color="{sc}"><b>[{icon}]</b></font>  Sc\u00e9nario {i} : {sname}',
            toc_style
        ))
    story.append(Spacer(1, 20))
    story.append(Paragraph("<i>D\u00e9tail de chaque sc\u00e9nario dans les pages suivantes...</i>", body_small))

    story.append(PageBreak())

    # ═══════════════════════════════════════════════════════════════════
    # DETAILED SCENARIO PAGES
    # ═══════════════════════════════════════════════════════════════════
    for index, scenario in enumerate(scenarios_results, 1):
        scenario_name = _safe_text(scenario.get("scenario_name") or scenario.get("nomSenario") or f"Scenario {index}")
        scenario_status = (scenario.get("status") or "UNKNOWN").upper()
        duration_ms = int(scenario.get("duration_ms", 0) or 0)
        error_message = scenario.get("error_message", "")
        steps = scenario.get("steps", []) or []

        # ── Scenario Header ────────────────────────────────────────────
        # Colored banner
        banner_data = [[
            Paragraph(f'<font color="white"><b>Sc\u00e9nario {index}</b></font>', ParagraphStyle('BanH', parent=body, fontSize=14, textColor=colors.white, fontName=_FONT_B)),
            Paragraph(f'<font color="white">{scenario_name}</font>', ParagraphStyle('BanN', parent=body, fontSize=11, textColor=colors.white, fontName=_FONT)),
            Paragraph(f'<font color="white"><b>[{status_icon(scenario_status)}] {scenario_status}</b></font>', ParagraphStyle('BanS', parent=body, fontSize=11, textColor=colors.white, fontName=_FONT_B, alignment=TA_RIGHT)),
        ]]
        banner = Table(banner_data, colWidths=[90, 260, 150])
        banner.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, -1), status_color(scenario_status)),
            ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
            ('TOPPADDING', (0, 0), (-1, -1), 10),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 10),
            ('LEFTPADDING', (0, 0), (0, 0), 12),
            ('RIGHTPADDING', (-1, 0), (-1, 0), 12),
        ]))
        story.append(banner)
        story.append(Spacer(1, 8))

        # ── Scenario Info Row ──────────────────────────────────────────
        info_data = [[
            Paragraph(f"<b>Dur\u00e9e :</b> {duration_ms} ms ({duration_ms/1000:.1f}s)", body_small),
            Paragraph(f"<b>\u00c9tapes :</b> {len(steps)}", body_small),
            Paragraph(f"<b>R\u00e9ussies :</b> {sum(1 for s in steps if s.get('status')=='PASSED')}", body_small),
            Paragraph(f"<b>\u00c9chou\u00e9es :</b> {sum(1 for s in steps if s.get('status')!='PASSED')}", body_small),
        ]]
        info_tbl = Table(info_data, colWidths=[130, 100, 120, 120])
        info_tbl.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, -1), status_bg(scenario_status)),
            ('BOX', (0, 0), (-1, -1), 0.5, status_color(scenario_status)),
            ('TOPPADDING', (0, 0), (-1, -1), 6),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
            ('LEFTPADDING', (0, 0), (-1, -1), 8),
        ]))
        story.append(info_tbl)
        story.append(Spacer(1, 10))

        # ── Scenario Error ──────────────────────────────────────────────
        if error_message:
            story.append(Paragraph("<b>Erreur du sc\u00e9nario :</b>", body_bold))
            err_text = _safe_text(error_message)
            story.append(Paragraph(err_text, ParagraphStyle('ScenarioErrFull', parent=error_style, wordWrap='CJK')))
            story.append(Spacer(1, 8))

        # ── Steps Detail ───────────────────────────────────────────────
        if steps:
            story.append(Paragraph("<b>D\u00e9tail des \u00e9tapes</b>", h3))
            story.append(Spacer(1, 4))

            for i, step in enumerate(steps, 1):
                step_status = (step.get("status") or "UNKNOWN").upper()
                step_text = _safe_text(step.get("step_text") or step.get("action") or "")
                step_duration = int(step.get("duration_ms", 0) or 0)
                step_error = step.get("error_message", "")

                # Step row with colored left border
                step_icon = status_icon(step_status)
                step_keyword = step_text.split()[0] if step_text else ""
                step_rest = " ".join(step_text.split()[1:]) if len(step_text.split()) > 1 else step_text

                row_data = [[
                    Paragraph(f"<b>{i}</b>", ParagraphStyle('StepNum', parent=body, fontSize=10, alignment=TA_CENTER)),
                    Paragraph(f"<b>{step_icon}</b>", ParagraphStyle('StepIcon', parent=body, fontSize=10, alignment=TA_CENTER, textColor=status_color(step_status), fontName=_FONT_B)),
                    Paragraph(
                        f"<b>{_safe_text(step_keyword)}</b> {_safe_text(step_rest)}",
                        ParagraphStyle('StepTextFull', parent=body_small, leading=12, wordWrap='CJK')
                    ),
                    Paragraph(f"{step_duration}ms", ParagraphStyle('StepDur', parent=body_small, alignment=TA_RIGHT)),
                ]]

                row_tbl = Table(row_data, colWidths=[30, 35, 340, 65])
                row_style_cmds = [
                    ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
                    ('TOPPADDING', (0, 0), (-1, -1), 5),
                    ('BOTTOMPADDING', (0, 0), (-1, -1), 5),
                    ('LEFTPADDING', (0, 0), (-1, -1), 4),
                    ('RIGHTPADDING', (0, 0), (-1, -1), 4),
                    ('LINEBELOW', (0, 0), (-1, 0), 0.3, colors.HexColor('#e2e8f0')),
                ]
                # Color the entire row background based on status
                row_style_cmds.append(('BACKGROUND', (0, 0), (-1, -1), status_bg(step_status)))
                # Left colored border
                row_style_cmds.append(('LINEBEFORE', (0, 0), (0, -1), 3, status_color(step_status)))
                row_tbl.setStyle(TableStyle(row_style_cmds))
                story.append(row_tbl)

                # Error detail for failed steps
                if step_error:
                    err_str = str(step_error)
                    err_row = [[
                        Paragraph("", body_small),
                        Paragraph("", body_small),
                        Paragraph(
                            f"<font color='#991b1b'>{_safe_text(err_str)}</font>",
                            ParagraphStyle('StepErr', parent=body_small, textColor=colors.HexColor('#991b1b'), fontName=_FONT, fontSize=8, leading=10, wordWrap='CJK')
                        ),
                        Paragraph("", body_small),
                    ]]
                    err_tbl = Table(err_row, colWidths=[30, 35, 340, 65])
                    err_tbl.setStyle(TableStyle([
                        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#fef2f2')),
                        ('LINEBEFORE', (0, 0), (0, -1), 3, colors.HexColor('#dc2626')),
                        ('TOPPADDING', (0, 0), (-1, -1), 3),
                        ('BOTTOMPADDING', (0, 0), (-1, -1), 3),
                        ('LEFTPADDING', (0, 0), (-1, -1), 4),
                    ]))
                    story.append(err_tbl)

                # Screenshot for this step
                step_screenshot = step.get("screenshot") or step.get("screenshot_base64")
                if step_screenshot:
                    try:
                        image_data = base64.b64decode(step_screenshot)
                        image_buffer = io.BytesIO(image_data)
                        img_row = [[
                            Paragraph("", body_small),
                            Paragraph("", body_small),
                            RLImage(image_buffer, width=4.2 * inch, height=2.3 * inch),
                            Paragraph("", body_small),
                        ]]
                        img_tbl = Table(img_row, colWidths=[30, 35, 340, 65])
                        img_tbl.setStyle(TableStyle([
                            ('LINEBEFORE', (0, 0), (0, -1), 3, status_color(step_status)),
                            ('TOPPADDING', (0, 0), (-1, -1), 4),
                            ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
                            ('LEFTPADDING', (0, 0), (-1, -1), 4),
                            ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#f8fafc')),
                        ]))
                        story.append(img_tbl)
                    except Exception:
                        pass

        # ── Scenario-level Screenshots ─────────────────────────────────
        scenario_screenshots = scenario.get("screenshots", []) or []
        if scenario_screenshots:
            story.append(Spacer(1, 10))
            story.append(Paragraph("<b>Captures d'\u00e9cran du sc\u00e9nario</b>", h3))
            for idx_ss, screenshot_b64 in enumerate(scenario_screenshots, 1):
                try:
                    image_data = base64.b64decode(screenshot_b64)
                    image_buffer = io.BytesIO(image_data)
                    story.append(Spacer(1, 4))
                    story.append(Paragraph(f"Capture {idx_ss}", body_small))
                    story.append(RLImage(image_buffer, width=5.5 * inch, height=3.0 * inch))
                except Exception:
                    story.append(Paragraph(f"Capture {idx_ss} : impossible de d\u00e9coder", body_small))

        # Page break between scenarios
        if index < len(scenarios_results):
            story.append(PageBreak())

    # ── Build with page numbers ────────────────────────────────────────
    def add_page_number(canvas_obj, doc_obj):
        canvas_obj.saveState()
        # Header line
        canvas_obj.setStrokeColor(colors.HexColor('#e2e8f0'))
        canvas_obj.setLineWidth(0.5)
        canvas_obj.line(40, page_h - 40, page_w - 40, page_h - 40)
        # Footer
        canvas_obj.setFont(_FONT, 8)
        canvas_obj.setFillColor(colors.HexColor('#94a3b8'))
        canvas_obj.drawString(40, 25, f"Rapport d'ex\u00e9cution Selenium - {formatted_ts}")
        canvas_obj.drawRightString(page_w - 40, 25, f"Page {doc_obj.page}")
        canvas_obj.restoreState()

    doc.build(story, onFirstPage=add_page_number, onLaterPages=add_page_number)
    return base64.b64encode(pdf_buffer.getvalue()).decode()

def generate_rich_report(scenarios_results: List[Dict[str, Any]],
                         project_metadata: Dict[str, Any]) -> Dict[str, str]:
    """
    Generate complete report package with HTML and PDF

    Returns:
        {
            "html": "<!DOCTYPE html>...",
            "pdf_base64": "JVBERi0xLjQK...",
            "summary": {...}
        }
    """

    # Generate HTML report
    html_content = generate_html_report(scenarios_results, project_metadata)

    # Primary strategy: build a fully detailed PDF directly from structured data.
    pdf_mode = "reportlab-detailed"
    pdf_base64 = _build_detailed_pdf_report(scenarios_results, project_metadata)

    # Fallback strategy: HTML->PDF conversion if direct detailed generation fails.
    if not pdf_base64:
        pdf_mode = "html-conversion-fallback"
        pdf_base64 = generate_pdf_from_html(html_content)

    # Last-resort strategy: ensure we always return something.
    if not pdf_base64:
        pdf_mode = "html-as-base64-fallback"
        pdf_base64 = base64.b64encode(html_content.encode('utf-8')).decode()

    # Calculate summary
    total_scenarios = len(scenarios_results)
    passed = sum(1 for s in scenarios_results if s.get("status") == "PASSED")
    failed = sum(1 for s in scenarios_results if s.get("status") == "FAILED")

    summary = {
        "total_scenarios": total_scenarios,
        "passed_scenarios": passed,
        "failed_scenarios": failed,
        "success_rate": f"{(passed/total_scenarios*100):.1f}%" if total_scenarios > 0 else "0%",
        "total_duration_ms": sum(s.get("duration_ms", 0) for s in scenarios_results),
        "timestamp": datetime.now().isoformat()
    }

    return {
        "html": html_content,
        "pdf_base64": pdf_base64,
        "summary": summary,
        "pdf_generation_mode": pdf_mode
    }
