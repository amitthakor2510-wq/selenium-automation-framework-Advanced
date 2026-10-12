@ui @saucedemo
Feature: SauceDemo login

  @smoke @regression
  Scenario: Valid user can log in
    Given the user is on the SauceDemo login page
    When the user logs in with username "standard_user" and password "secret_sauce"
    Then the user lands on the inventory page

  @regression
  Scenario Outline: Invalid credentials are rejected
    Given the user is on the SauceDemo login page
    When the user logs in with username "<username>" and password "<password>"
    Then an error message is shown

    Examples:
      | username        | password     |
      | standard_user   | wrong_pass   |
      | locked_out_user | secret_sauce |
