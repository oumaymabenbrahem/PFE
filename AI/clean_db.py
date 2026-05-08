import os
import psycopg2

def require_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise RuntimeError(f"{name} environment variable is required")
    return value

conn = psycopg2.connect(
    dbname=require_env("DB_NAME"),
    user=require_env("DB_USERNAME"),
    password=require_env("DB_PASSWORD"),
    host=os.getenv("DB_HOST", "localhost"),
    port=os.getenv("DB_PORT", "5432"),
)
c = conn.cursor()
c.execute("UPDATE test_scripts SET script_content = '# --- Généré par CodeT5 (Local) à partir des prédictions Random Forest ---\\nfrom selenium import webdriver\\ndriver = webdriver.Chrome()\\ndriver.get(\"https://example.com\")\\n# Script test auto-remplacé suite à une ancienne erreur de JSON en base\\n# Veuillez CRÉER UN NOUVEAU PROJET pour tester.' WHERE script_content LIKE '[%';")
conn.commit()
print("DB cleaned of old JSON legacy scripts.")
