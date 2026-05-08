import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report
from sklearn.pipeline import Pipeline
from sklearn.base import BaseEstimator, TransformerMixin
import joblib

# Mots-clés de footer à ignorer (bruit)
FOOTER_KEYWORDS = {
    "conditions d'utilisation", "confidentialité", "droits d'auteur",
    "presse", "publicité", "développeurs", "créateurs", "présentation",
    "règles et sécurité", "premiers pas", "nous contacter", "politique",
    "mentions légales", "cookies", "copyright", "terms", "privacy",
    "about", "press", "advertise", "careers"
}

def is_noise(el: dict) -> bool:
    """Retourne True si l'élément (converti en dict Python) est du bruit (à ignorer)."""
    el_type = (el.get("type") or "").lower()
    text    = (el.get("text") or "").strip().lower()
    name    = (el.get("name") or "").strip().lower()
    
    if el_type == "hidden":
        return True
    if any(k in name for k in ["token", "csrf", "timestamp", "secret", "webauthn", "javascript-support", "iuvpaa", "required_field"]):
        return True
    if any(k in text for k in FOOTER_KEYWORDS):
        return True
    if not text and not name and not el.get("id"):
        return True
    return False

def filter_noisy_elements(raw_elements_dicts: list) -> list:
    """Filtre la liste de dictionnaires d'éléments interactifs en supprimant le bruit et les doublons."""
    elements_dicts = []
    seen_texts = set()
    for el in raw_elements_dicts:
        if is_noise(el): continue
        
        key = (str(el.get("text", "")).strip().lower(), str(el.get("name", "")).strip().lower())
        if key in seen_texts and key != ("", ""): continue
        seen_texts.add(key)
        elements_dicts.append(el)
    return elements_dicts

# 1. Chargement du dataset (tu pourras ajouter plus d'exemples)
df = pd.read_csv("elements_dataset.csv")

# Remplacement des valeurs vides (NaN) par des chaînes vides
df.fillna('', inplace=True)

# 2. Extracteur de caractéristiques (Feature Engineering)
# Convertit nos attributs HTML bruts (tag, id, etc.) en caractéristiques numériques (1 ou 0)
class FeatureExtractor(BaseEstimator, TransformerMixin):
    def fit(self, X, y=None):
        return self
    
    def transform(self, X):
        X_transformed = pd.DataFrame()
        
        # Est-ce un élément masqué ?
        X_transformed['is_hidden'] = (X['type'] == 'hidden').astype(int)
        
        # Normalise et combine le texte pour chercher des mots clés
        combined_text = (X['name'] + ' ' + X['id'] + ' ' + X['text']).str.lower()
        
        # Mots clés associés aux identifiants (login, email, user)
        X_transformed['has_login_kw'] = combined_text.str.contains('login|user|email').astype(int)
        
        # Mots clés associés aux mots de passe
        X_transformed['has_password_kw'] = combined_text.str.contains('pass').astype(int)
        
        # Mots clés pour les boutons de soumission / validation
        X_transformed['has_submit_kw'] = combined_text.str.contains('submit|commit|sign|log|connect').astype(int)
        
        # Mots clés pour la recherche
        X_transformed['has_search_kw'] = combined_text.str.contains('search|query|q').astype(int)
        
        # Est-ce un simple lien ?
        X_transformed['is_link'] = (X['tag'] == 'a').astype(int)
        
        return X_transformed

# 3. Création du pipeline complet : feature engineering + algorithme RandomForest
pipeline = Pipeline([
    ('features', FeatureExtractor()),
    ('rf', RandomForestClassifier(n_estimators=200, random_state=42))
])

# Séparation des caractéristiques X (features) et Y (labels/réponses attendues)
X = df.drop("label", axis=1)
y = df["label"]

# Division en jeu d'entraînement (80%) et jeu de test (20%)
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# Entraînement du modèle
pipeline.fit(X_train, y_train)

# 4. Évaluation du modèle pour voir sa précision
print("--- Rapport de classification sur le jeu de test ---")
predictions = pipeline.predict(X_test)
print(classification_report(y_test, predictions, zero_division=0))

# 5. Sauvegarde du modèle entraîné (joblib)
joblib.dump(pipeline, "element_role_model_v1.pkl")
print("[OK] Modèle sauvegardé avec succès sous 'element_role_model_v1.pkl'.")
print("Prêt à être intégré dans votre API FastAPI pour l'analyse des éléments !")
