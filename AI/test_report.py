
import sys
import os
import base64
from datetime import datetime

# Add AI directory to path to import local modules
sys.path.append(r'c:\Users\LENOVO\Desktop\opencode\PFE_ST2i - Copie\PFE_ST2i\AI')

try:
    from report_generator import generate_rich_report
    print("SUCCESS: Successfully imported generate_rich_report")
except ImportError as e:
    print(f"ERROR: Failed to import generate_rich_report: {e}")
    sys.exit(1)

# Dummy data
scenarios_results = [
    {
        "scenario_name": "Test Scenario 1",
        "status": "PASSED",
        "duration_ms": 1500,
        "steps": [
            {"step_text": "Given I open the page", "status": "PASSED", "duration_ms": 500},
            {"step_text": "Then I see the title", "status": "PASSED", "duration_ms": 1000}
        ],
        "screenshots": [],
        "error_message": ""
    }
]

project_metadata = {
    "project_name": "Test Project",
    "url": "http://example.com",
    "timestamp": datetime.now().isoformat()
}

print("Testing report generation...")
try:
    report = generate_rich_report(scenarios_results, project_metadata)
    print(f"SUCCESS: Report generated successfully")
    print(f"  PDF size: {len(report.get('pdf_base64', ''))} characters")
    print(f"  Mode: {report.get('pdf_generation_mode', 'unknown')}")
    
    if not report.get('pdf_base64'):
        print("ERROR: PDF base64 is EMPTY!")
    else:
        print("SUCCESS: PDF base64 is present")
        
except Exception as e:
    print(f"ERROR: Error during report generation: {e}")
    import traceback
    traceback.print_exc()
