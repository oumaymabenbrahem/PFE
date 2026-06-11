import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.neighbors import NearestNeighbors
from pathlib import Path

def test_knn_logic():
    csv_path = Path("form_test_data.csv")
    if not csv_path.exists():
        print("❌ CSV not found")
        return

    df = pd.read_csv(csv_path)
    descriptions = df['description'].fillna('').tolist()
    
    vectorizer = TfidfVectorizer()
    knn = NearestNeighbors(n_neighbors=1, metric='cosine')
    
    X = vectorizer.fit_transform(descriptions)
    knn.fit(X)
    
    test_cases = [
        {"type": "email", "name": "input_email", "placeholder": "Votre email"},
        {"type": "password", "name": "pwd", "placeholder": "Mot de passe"},
        {"type": "text", "name": "prenom", "placeholder": "First name"},
        {"type": "tel", "name": "phone", "placeholder": "Contact"},
        {"type": "number", "name": "age", "placeholder": "Votre age"},
        {"type": "text", "name": "city", "placeholder": "Ville"}
    ]
    
    print(f"\n--- Testing KNN with {len(descriptions)} entries ---\n")
    
    for el in test_cases:
        query = f"{el['type']} {el['name']} {el['placeholder']}".lower()
        query_vec = vectorizer.transform([query])
        distances, indices = knn.kneighbors(query_vec)
        
        best_match_idx = indices[0][0]
        value = df.iloc[best_match_idx]['test_value']
        match_desc = df.iloc[best_match_idx]['description']
        dist = distances[0][0]
        
        print(f"Query: '{query}'")
        print(f"  -> Match: '{match_desc}'")
        print(f"  -> Value: '{value}' (dist: {dist:.4f})")
        print("-" * 30)

if __name__ == "__main__":
    test_knn_logic()
