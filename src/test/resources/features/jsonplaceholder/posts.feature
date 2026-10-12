@api @jsonplaceholder
Feature: JSONPlaceholder posts API

  @smoke @regression
  Scenario: Fetch a single post
    When the user sends a GET request to "/posts/1"
    Then the response status should be 200
    And the response field "id" should be "1"
    And the response field "title" should not be empty

  @regression
  Scenario: Create a post
    When the user sends a POST request to "/posts" with the body:
      """
      {"title": "hello", "body": "world", "userId": 1}
      """
    Then the response status should be 201
    And the response field "title" should be "hello"
