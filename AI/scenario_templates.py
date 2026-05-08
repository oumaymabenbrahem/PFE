"""
Gherkin Scenario Templates for different page types detected by ML analysis.
Each template is customized with actual element selectors and test data.
"""

from typing import Dict, List, Any
from faker import Faker

fake = Faker()


class ScenarioTemplates:
    """Provides predefined Gherkin scenarios for common page types"""

    @staticmethod
    def get_login_scenarios(elements: Dict[str, Any], elements_dict: List[Dict]) -> List[Dict]:
        """
        Generate login form test scenarios
        """
        scenarios = []

        # Find actual element selectors
        username_selector = ScenarioTemplates._find_selector(elements_dict, ['login', 'user', 'email'])
        password_selector = ScenarioTemplates._find_selector(elements_dict, ['pass'])
        submit_selector = ScenarioTemplates._find_selector(elements_dict, ['submit', 'sign', 'log', 'connect'])

        # Generate realistic test data
        valid_email = fake.email()
        valid_password = fake.password(length=12, special_chars=True)
        invalid_password = "wrong_password_" + fake.word()

        scenarios.append({
            "nomSenario": "Connexion réussie avec credentials valides",
            "type": "Fonctionnel",
            "description": "Vérifier que l'utilisateur peut se connecter avec des identifiants valides",
            "resultatAttendu": "L'utilisateur est redirigé vers le dashboard ou la page d'accueil",
            "senario": f"""Feature: Authentification
  Scenario: Connexion réussie
    Given l'utilisateur est sur la page de connexion
    When l'utilisateur saisit le username "{valid_email}" dans {username_selector}
    And l'utilisateur saisit le mot de passe "{valid_password}" dans {password_selector}
    And l'utilisateur clique sur {submit_selector}
    Then l'utilisateur devrait être redirigé vers la page d'accueil ou dashboard
    And un message de bienvenue ou notification devrait apparaitre""",
            "selected": True
        })

        scenarios.append({
            "nomSenario": "Connexion échouée (mot de passe invalide)",
            "type": "Fonctionnel",
            "description": "Vérifier le rejet avec mot de passe incorrect",
            "resultatAttendu": "Un message d'erreur s'affiche, l'utilisateur reste sur la page",
            "senario": f"""Feature: Authentification
  Scenario: Connexion échouée - mauvais mot de passe
    Given l'utilisateur est sur la page de connexion
    When l'utilisateur saisit le username "{valid_email}" dans {username_selector}
    And l'utilisateur saisit le mot de passe "{invalid_password}" dans {password_selector}
    And l'utilisateur clique sur {submit_selector}
    Then un message d'erreur d'identification s'affiche
    And l'utilisateur reste sur la page de connexion""",
            "selected": True
        })

        scenarios.append({
            "nomSenario": "Champ username vide",
            "type": "Validation",
            "description": "Vérifier la validation avec username vide",
            "resultatAttendu": "Un message d'erreur 'Champ requis' s'affiche",
            "senario": f"""Feature: Validation
  Scenario: Validation - Champ username requis
    Given l'utilisateur est sur la page de connexion
    When l'utilisateur laisse le username vide
    And l'utilisateur saisit le mot de passe "{valid_password}" dans {password_selector}
    And l'utilisateur clique sur {submit_selector}
    Then un message de validation 'Champ requis' devrait apparaitre pour le username
    And le formulaire ne devrait pas être soumis""",
            "selected": True
        })

        scenarios.append({
            "nomSenario": "Champ password vide",
            "type": "Validation",
            "description": "Vérifier la validation avec mot de passe vide",
            "resultatAttendu": "Un message d'erreur 'Champ requis' s'affiche",
            "senario": f"""Feature: Validation
  Scenario: Validation - Champ password requis
    Given l'utilisateur est sur la page de connexion
    When l'utilisateur saisit le username "{valid_email}" dans {username_selector}
    And l'utilisateur laisse le mot de passe vide
    And l'utilisateur clique sur {submit_selector}
    Then un message de validation 'Champ requis' devrait apparaitre pour le password
    And le formulaire ne devrait pas être soumis""",
            "selected": False
        })

        return scenarios

    @staticmethod
    def get_form_scenarios(elements: Dict[str, Any], elements_dict: List[Dict]) -> List[Dict]:
        """
        Generate general form test scenarios
        """
        scenarios = []

        # Find form elements
        input_fields = [el for el in elements_dict if el.get('tag') == 'input']
        required_fields = [el for el in input_fields if 'required' in el.get('aria-label', '').lower()]
        submit_selector = ScenarioTemplates._find_selector(elements_dict, ['submit', 'send', 'post'])

        scenarios.append({
            "nomSenario": "Soumission du formulaire avec données valides",
            "type": "Fonctionnel",
            "description": "Vérifier que le formulaire accepte les données valides",
            "resultatAttendu": "Le formulaire est soumis avec succès et confirmation affichée",
            "senario": f"""Feature: Formulaire
  Scenario: Soumission valide
    Given l'utilisateur est sur le formulaire
    When l'utilisateur remplisse tous les champs requis avec des données valides
    And l'utilisateur clique sur {submit_selector}
    Then le formulaire devrait être soumis avec succès
    And une confirmation ou redirection devrait apparaitre""",
            "selected": True
        })

        if required_fields:
            scenarios.append({
                "nomSenario": "Champs requis - Validation",
                "type": "Validation",
                "description": "Vérifier que les champs requis ne peuvent pas être vides",
                "resultatAttendu": "Messages de validation pour chaque champ requis",
                "senario": f"""Feature: Validation
  Scenario: Champs requis
    Given l'utilisateur est sur le formulaire
    When l'utilisateur laisse les champs requis vides
    And l'utilisateur clique sur {submit_selector}
    Then des messages de validation 'Champ requis' devraient apparaitre
    And le formulaire ne devrait pas être soumis""",
                "selected": True
            })

        return scenarios

    @staticmethod
    def get_search_scenarios(elements: Dict[str, Any], elements_dict: List[Dict]) -> List[Dict]:
        """
        Generate search functionality test scenarios
        """
        scenarios = []

        search_selector = ScenarioTemplates._find_selector(elements_dict, ['search', 'query', 'q'])
        submit_selector = ScenarioTemplates._find_selector(elements_dict, ['search', 'submit'])

        test_query = fake.word()

        scenarios.append({
            "nomSenario": "Recherche avec résultats valides",
            "type": "Fonctionnel",
            "description": "Vérifier la recherche retourne des résultats appropriés",
            "resultatAttendu": "Une liste de résultats s'affiche",
            "senario": f"""Feature: Recherche
  Scenario: Recherche avec résultats
    Given l'utilisateur est sur la page d'accueil
    When l'utilisateur saisit "{test_query}" dans {search_selector}
    And l'utilisateur clique sur {submit_selector}
    Then une liste de résultats devrait s'afficher
    And les résultats devraient contenir le terme recherché""",
            "selected": True
        })

        scenarios.append({
            "nomSenario": "Recherche avec aucun résultat",
            "type": "Fonctionnel",
            "description": "Vérifier le comportement avec aucun résultat",
            "resultatAttendu": "Un message 'Aucun résultat' s'affiche",
            "senario": f"""Feature: Recherche
  Scenario: Aucun résultat
    Given l'utilisateur est sur la page d'accueil
    When l'utilisateur saisit "xyzabc_nonexistent_query_12345" dans {search_selector}
    And l'utilisateur clique sur {submit_selector}
    Then un message 'Aucun résultat trouvé' devrait s'afficher""",
            "selected": False
        })

        return scenarios

    @staticmethod
    def get_ecommerce_scenarios(elements: Dict[str, Any], elements_dict: List[Dict]) -> List[Dict]:
        """
        Generate e-commerce flow test scenarios
        """
        scenarios = []

        add_to_cart_selector = ScenarioTemplates._find_selector(elements_dict, ['add', 'cart', 'basket'])
        checkout_selector = ScenarioTemplates._find_selector(elements_dict, ['checkout', 'pay', 'order'])

        scenarios.append({
            "nomSenario": "Ajouter au panier",
            "type": "Fonctionnel",
            "description": "Vérifier l'ajout d'un produit au panier",
            "resultatAttendu": "Le panier se met à jour avec le produit ajouté",
            "senario": f"""Feature: E-commerce
  Scenario: Ajouter un produit au panier
    Given l'utilisateur est sur une page produit
    When l'utilisateur clique sur {add_to_cart_selector}
    Then le panier devrait afficher le nouveau produit
    And un message de confirmation devrait apparaitre""",
            "selected": True
        })

        scenarios.append({
            "nomSenario": "Processus de paiement",
            "type": "Fonctionnel",
            "description": "Vérifier le flux de paiement",
            "resultatAttendu": "L'utilisateur est redirigé vers la page de confirmation",
            "senario": f"""Feature: E-commerce
  Scenario: Paiement réussi
    Given l'utilisateur a un panier non vide
    When l'utilisateur clique sur {checkout_selector}
    And l'utilisateur remplit les infos de livraison
    And l'utilisateur remplit les infos de paiement
    And l'utilisateur confirme la commande
    Then une page de confirmation devrait s'afficher
    And le numéro de commande devrait être visible""",
            "selected": False
        })

        return scenarios

    @staticmethod
    def get_generic_scenarios(elements: Dict[str, Any], elements_dict: List[Dict]) -> List[Dict]:
        """
        Generate generic navigation and interaction scenarios
        """
        scenarios = []

        scenarios.append({
            "nomSenario": "Navigation - Charger la page",
            "type": "Fonctionnel",
            "description": "Vérifier que la page charge sans erreur",
            "resultatAttendu": "La page s'affiche correctement sans erreur 404 ou 500",
            "senario": """Feature: Navigation
  Scenario: Charger la page
    Given l'utilisateur accède à la page
    Then la page devrait charger sans erreur
    And le statut HTTP devrait être 200
    And le contenu devrait être visible""",
            "selected": True
        })

        # Count interactive elements
        links = [el for el in elements_dict if el.get('tag') == 'a']
        buttons = [el for el in elements_dict if el.get('tag') == 'button']

        if links:
            scenarios.append({
                "nomSenario": "Navigation - Vérifier les liens",
                "type": "Fonctionnel",
                "description": f"Vérifier les {len(links)} liens trouvés sur la page",
                "resultatAttendu": "Tous les liens fonctionnent et ne retournent pas 404",
                "senario": """Feature: Navigation
  Scenario: Vérifier les liens
    Given l'utilisateur est sur la page
    When l'utilisateur clique sur chaque lien
    Then chaque lien devrait ouvrir une page valide
    And aucune page 404 ne devrait s'afficher""",
                "selected": False
            })

        return scenarios

    @staticmethod
    def _find_selector(elements: List[Dict], keywords: List[str]) -> str:
        """
        Find an appropriate CSS selector for an element matching keywords
        """
        for el in elements:
            for keyword in keywords:
                text = (el.get('text', '') + el.get('name', '') + el.get('id', '')).lower()
                if keyword.lower() in text:
                    if el.get('id'):
                        return f"#{el.get('id')}"
                    elif el.get('name'):
                        return f"[name='{el.get('name')}']"
                    elif el.get('aria-label'):
                        return f"[aria-label='{el.get('aria-label')}']"

        # Fallback to first element of that type
        for keyword in keywords:
            for el in elements:
                if keyword.lower() in el.get('tag', '').lower():
                    if el.get('id'):
                        return f"#{el.get('id')}"
                    return el.get('tag', 'button')

        return "[not-found]"

    @staticmethod
    def get_scenarios_for_page_type(page_type: str, elements: Dict[str, Any],
                                    elements_dict: List[Dict]) -> List[Dict]:
        """
        Route to appropriate scenario generator based on detected page type
        """
        if page_type == "login_form":
            return ScenarioTemplates.get_login_scenarios(elements, elements_dict)
        elif page_type == "form":
            return ScenarioTemplates.get_form_scenarios(elements, elements_dict)
        elif page_type == "search":
            return ScenarioTemplates.get_search_scenarios(elements, elements_dict)
        elif page_type == "ecommerce":
            return ScenarioTemplates.get_ecommerce_scenarios(elements, elements_dict)
        else:
            return ScenarioTemplates.get_generic_scenarios(elements, elements_dict)
