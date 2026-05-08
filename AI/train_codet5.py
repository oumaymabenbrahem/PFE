import pandas as pd
import torch
from datasets import Dataset
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM, Seq2SeqTrainer, Seq2SeqTrainingArguments

# 1. Charger et enrichir le dataset fourni
# (Ici on charge ton fichier, et on ajoute de nouveaux exemples de scénarios)
df = pd.read_csv("dataset_codet5_selenium.csv")

# Ajout de NOUVEAUX exemples de haute qualité
new_examples = [
    {
        "scenario_description": "ecommerce_search: field #search-input, button .search-btn | search specific product",
        "selenium_script": "driver.find_element(By.ID, 'search-input').send_keys('clavier qwertz')\ndriver.find_element(By.CSS_SELECTOR, '.search-btn').click()\nWebDriverWait(driver, 10).until(EC.presence_of_element_located((By.CLASS_NAME, 'product-list')))\nassert len(driver.find_elements(By.CLASS_NAME, 'product-item')) > 0"
    },
    {
        "scenario_description": "navigation_menu: link #nav-contact | click to contact page",
        "selenium_script": "driver.find_element(By.ID, 'nav-contact').click()\nWebDriverWait(driver, 5).until(EC.url_contains('contact'))\nassert driver.find_element(By.ID, 'contact-form').is_displayed()"
    },
    {
        "scenario_description": "auth_login: field #username, field #password, button #login-btn | login admin",
        "selenium_script": "driver.find_element(By.ID, 'username').send_keys('admin')\ndriver.find_element(By.ID, 'password').send_keys('su-pass')\ndriver.find_element(By.ID, 'login-btn').click()\nassert 'admin-panel' in driver.current_url"
    }
]

df_new = pd.DataFrame(new_examples)
df_augmented = pd.concat([df, df_new], ignore_index=True)

# Sauvegarde pour référence
df_augmented.to_csv("dataset_codet5_selenium_augmented.csv", index=False)

# Convertir en Dataset Hugging Face
hf_dataset = Dataset.from_pandas(df_augmented)

# 2. Préparation du tokeniseur et du petit modèle CodeT5 (Salesforce/codet5-small)
model_name = "Salesforce/codet5-small"
tokenizer = AutoTokenizer.from_pretrained(model_name)
model = AutoModelForSeq2SeqLM.from_pretrained(model_name)

def preprocess_function(examples):
    # Les entrées sont nos descriptions (éléments classifiés + but)
    inputs = ["Generate Selenium script for: " + ex for ex in examples["scenario_description"]]
    targets = examples["selenium_script"]
    
    # Tokenisation
    model_inputs = tokenizer(inputs, max_length=128, padding="max_length", truncation=True)
    with tokenizer.as_target_tokenizer():
        labels = tokenizer(targets, max_length=256, padding="max_length", truncation=True)
        
    model_inputs["labels"] = labels["input_ids"]
    return model_inputs

# Application du preprocessing
tokenized_dataset = hf_dataset.map(preprocess_function, batched=True)

# 3. Paramètres d'entraînement
training_args = Seq2SeqTrainingArguments(
    output_dir="./results",
    evaluation_strategy="no",
    learning_rate=2e-5,
    per_device_train_batch_size=2,
    weight_decay=0.01,
    save_total_limit=1,
    num_train_epochs=10,  # Plus d'époques pour un petit dataset
    predict_with_generate=True,
    fp16=torch.cuda.is_available()  # Utilise le GPU si dispo
)

# 4. Entraîneur
trainer = Seq2SeqTrainer(
    model=model,
    args=training_args,
    train_dataset=tokenized_dataset,
    tokenizer=tokenizer,
)

print("🚀 Début de l'entraînement de CodeT5...")
trainer.train()

# 5. Sauvegarde du modèle affiné localement
save_dir = "./codet5_finetuned_selenium"
model.save_pretrained(save_dir)
tokenizer.save_pretrained(save_dir)
print(f"✅ Modèle CodeT5 affiné et sauvegardé dans {save_dir}/")