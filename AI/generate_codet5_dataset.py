import pandas as pd
import random
import os

# Liste de structures typiques basées sur notre Random Forest et tes besoins Selenium
TEMPLATES = [
    # Login
    {
        "desc": "auth_login: field #{user_id}, field #{pass_id}, button #{btn_id} | valid credentials",
        "script": "driver.find_element(By.ID, '{user_id}').send_keys('user@test.com')\ndriver.find_element(By.ID, '{pass_id}').send_keys('Password123!')\ndriver.find_element(By.ID, '{btn_id}').click()\nWebDriverWait(driver, 10).until(EC.url_contains('dashboard'))\nassert 'dashboard' in driver.current_url"
    },
    {
        "desc": "auth_login: field #{user_id}, field #{pass_id}, button #{btn_id} | invalid credentials",
        "script": "driver.find_element(By.ID, '{user_id}').send_keys('wrong@test.com')\ndriver.find_element(By.ID, '{pass_id}').send_keys('wrongpassword')\ndriver.find_element(By.ID, '{btn_id}').click()\nerror_msg = WebDriverWait(driver, 5).until(EC.visibility_of_element_located((By.CLASS_NAME, 'error-message')))\nassert error_msg.is_displayed()"
    },
    # Recherche
    {
        "desc": "ecommerce_search: field #{search_id}, button #{search_btn} | search specific product",
        "script": "driver.find_element(By.ID, '{search_id}').send_keys('produit test')\ndriver.find_element(By.ID, '{search_btn}').click()\nWebDriverWait(driver, 10).until(EC.presence_of_element_located((By.CLASS_NAME, 'product-list')))\nassert len(driver.find_elements(By.CLASS_NAME, 'product-item')) > 0"
    },
    # Register
    {
        "desc": "auth_register: field #{user_id}, field #{pass_id}, button #{btn_id} | valid registration",
        "script": "driver.find_element(By.ID, '{user_id}').send_keys('newuser@test.com')\ndriver.find_element(By.ID, '{pass_id}').send_keys('TestPass123!')\ndriver.find_element(By.ID, '{btn_id}').click()\nWebDriverWait(driver, 10).until(EC.url_contains('verify'))\nassert 'verify' in driver.current_url"
    },
    # Navigation
    {
        "desc": "navigation_menu: link #{nav_link} | navigate to section",
        "script": "driver.find_element(By.ID, '{nav_link}').click()\nWebDriverWait(driver, 5).until(EC.url_changes(driver.current_url))\nassert '{nav_link_slug}' in driver.current_url"
    }
]

USER_IDS = ["username", "email", "login-input", "user", "identifier"]
PASS_IDS = ["password", "pwd", "pass-input", "secret", "user-pass"]
BTN_IDS = ["login-btn", "submit-login", "connect", "sign-in", "auth-button", "register-btn", "signup"]
SEARCH_IDS = ["search-bar", "q", "search-input", "query"]
SEARCH_BTNS = ["search-submit", "find-btn", "search-icon", "submit-search"]
NAV_LINKS = [("nav-home", "home"), ("nav-profile", "profile"), ("nav-settings", "settings"), ("nav-contact", "contact")]

generated_data = []

# Générer 1000 exemples !
for _ in range(1000):
    template = random.choice(TEMPLATES)
    
    user_id = random.choice(USER_IDS)
    pass_id = random.choice(PASS_IDS)
    btn_id = random.choice(BTN_IDS)
    search_id = random.choice(SEARCH_IDS)
    search_btn = random.choice(SEARCH_BTNS)
    nav_link, nav_link_slug = random.choice(NAV_LINKS)
    
    desc = template["desc"].format(
        user_id=user_id, pass_id=pass_id, btn_id=btn_id, 
        search_id=search_id, search_btn=search_btn, nav_link=nav_link
    )
    
    script = template["script"].format(
        user_id=user_id, pass_id=pass_id, btn_id=btn_id, 
        search_id=search_id, search_btn=search_btn, nav_link=nav_link, nav_link_slug=nav_link_slug
    )
    
    generated_data.append({"scenario_description": desc, "selenium_script": script})

df_1000 = pd.DataFrame(generated_data)
output_path = os.path.join("C:\\Users\\LENOVO\\Desktop\\PFE_ST2i\\AI", "dataset_codet5_selenium.csv")

df_1000.to_csv(output_path, index=False)
print(f"✅ Généré 1000 exemples d'entrainement dans {output_path}")
