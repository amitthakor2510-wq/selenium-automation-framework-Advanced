@mobile
Feature: Android Settings app

  @smoke @regression
  Scenario: Settings home screen loads
    Given the Android Settings app is open
    Then the Settings search bar is displayed

  @regression
  Scenario: Network and internet opens from the home list
    Given the Android Settings app is open
    When the user opens Network and internet
    Then the Settings home screen is no longer shown
