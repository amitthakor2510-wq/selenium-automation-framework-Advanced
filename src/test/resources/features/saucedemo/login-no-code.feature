@ui @saucedemo @no-code
Feature: SauceDemo login (no-code steps)
  Written only with the shared steps in CommonSteps; elements come from
  objectrepository/saucedemo.properties.

  @smoke @regression
  Scenario: Valid user reaches the product list
    Given the user opens the home page
    When the user types "standard_user" into "login.username"
    And the user types "secret_sauce" into "login.password"
    And the user clicks "login.submitButton"
    Then the url should contain "inventory"
    And "inventory.title" should have text "Products"

  @regression
  Scenario: Wrong password shows an error
    Given the user opens the home page
    When the user types "standard_user" into "saucedemo.login.username"
    And the user types "wrong_pass" into "saucedemo.login.password"
    And the user clicks "saucedemo.login.submitButton"
    Then the user should see "login.errorMessage"
    And "login.errorMessage" should have text "do not match"
