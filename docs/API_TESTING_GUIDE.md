# API Testing: Simple Steps

> **Looking for the fastest way?** For plain request/response tests with no code, one command and one HTML report, see [API_NO_CODE_GUIDE.md](API_NO_CODE_GUIDE.md) (`./Scripts/api-test.sh`). This page covers the Postman, security and rate-limit suites.

You will test your project's API with the framework, using the Postman collection from the developer.

Run every command in the project folder (the folder that contains `pom.xml`).

---

## Part 1: Get things from the developer

Ask the developer for these four things:

1. The **base URL** of a test server, for example `https://dev-api.company.com`.
2. The **Postman collection**, exported as v2.1 (a file ending `.postman_collection.json`).
3. The **Postman environment file** (ending `.postman_environment.json`), if the collection uses `{{variables}}`.
4. A **test username and password**, or a token, for that server.

---

## Part 2: Set up the framework

### Step 1. Check the project compiles

```bash
mvn test-compile
```

You should see `BUILD SUCCESS`.

If you see `duplicate class ... BaseApiTest`, there are two copies of that file. Run this and delete the extra one:

```bash
grep -rl "class BaseApiTest" src
```

Only `src/test/java/com/automation/sites/core/BaseApiTest.java` should be listed.

### Step 2. Create your project as a site

```bash
./Scripts/new-api-site.sh myproject https://dev-api.company.com
```

Use your own name instead of `myproject` and the real base URL.

This creates the file `src/test/resources/config/myproject.properties`. You will use the name `myproject` in every command below.

### Step 3. Install Newman

Newman is the tool that runs Postman collections.

```bash
npm install -g newman newman-reporter-htmlextra
newman --version
```

If you get a permissions error, run the first command again with `sudo` in front.

### Step 4. Put the Postman files in the project

Copy the collection file and the environment file into this folder:

```
src/test/resources/postman/
```

Do not rename the file endings (`.postman_collection.json` and `.postman_environment.json`).

Do not commit the environment file to Git if it contains real tokens or passwords.

---

## Part 3: Run the Postman collection

### Step 5. Tell the framework your base URL variable

Open the collection in Postman and look at a request's URL. If it starts with something like `{{baseUrl}}`, the variable is `baseUrl`.

Add this line to `src/test/resources/config/myproject.properties`:

```properties
postman.baseUrlVariable=baseUrl
```

Use the real variable name. If the URL is fully typed out and has no variable, skip this step.

### Step 6. Run it

```bash
mvn test -Dsite=myproject -DsuiteXmlFile=testng-suites/api-postman.xml
```

### Step 7. Read the result

Near the end, look for the line `Tests run: ..., Failures: ..., Skipped: ...`.

Maven may still print `BUILD SUCCESS` when tests fail, so ignore that line.

- No failures means every Postman check passed.
- A failure message lists each failed request and what went wrong.

### Step 8. Open the report

```bash
ls target/postman-reports
xdg-open target/postman-reports/<collection-name>.html
```

The file is named after your collection file.

Note: if the collection has no checks (nothing in each request's **Tests** tab in Postman), a pass only means every request got an answer. In that case add a few checks in Postman, such as "status is 200", or ask the developer to.

---

## Part 4: Run the security checks

### Step 9. Add your settings

Open `src/test/resources/config/myproject.properties` and add the endpoints that need a login, plus one way of logging in. Pick one option.

Option A: you have a fixed token.

```properties
api.security.protectedPaths=/users/me,/orders
api.auth.type=bearer
api.auth.token=PASTE_TOKEN_HERE
```

Option B: you log in with a username and password.

```properties
api.security.protectedPaths=/users/me,/orders
api.auth.type=jwt-login
api.auth.loginUrl=/auth/login
api.auth.username=tester
api.auth.tokenField=token
```

Use your real paths. `tokenField` is the name of the field that holds the token in the login reply.

Other login types are `oauth2-client-credentials` and `oauth2-password`. For these, add `api.auth.tokenUrl` and `api.auth.clientId` (and `api.auth.username` for `oauth2-password`).

### Step 10. Run it

Pass the password on the command line so it is not saved in a file. Leave out the `-Dapi.auth.password` part if you used a fixed token.

```bash
mvn test -Dsite=myproject -DsuiteXmlFile=testng-suites/api-security.xml -Dapi.auth.password=YOUR_PASSWORD
```

For OAuth, the secret is passed the same way: `-Dapi.auth.clientSecret=YOUR_SECRET`.

### Step 11. Read the result

- **Skipped** tests are normal. They mean you did not give that check the settings it needs, and the message says which one.
- A **failure** is a real finding about the API, for example a missing security header. Give failures to the developer.

What the security checks look at:

- Does a protected endpoint refuse a request with no token?
- Does it refuse a garbage token?
- Does it refuse a JWT whose signature was changed, or an unsigned JWT? (JWT tokens only)
- Are the security headers present (`nosniff`, HSTS, no `X-Powered-By`)?
- Does CORS refuse a hostile website?
- Is HTTP TRACE disabled?
- Does an unknown URL give a clean error with no stack trace?
- Are injection test strings handled without server errors? (needs `api.security.injectionTargets`)

To enable the injection check, add this to the config file:

```properties
api.security.injectionTargets=/search:q
```

The format is `/path:queryParameterName`. Use a real endpoint that takes a query parameter.

---

## Part 5: Look at the reports

### Extent report (one file you can email)

```bash
find target/extent-reports -name index.html
xdg-open <path printed above>
```

The security suite's report is under `target/extent-reports/myproject/chrome/api-security-checks/`.

The Postman suite does not make an Extent report. Use the Postman report from Step 8.

### Allure report (shows the request and response for every test)

```bash
mvn allure:serve
```

Stop it with Ctrl+C when you are done. In Allure, open **Suites**, then the suite, then a failed test, to see the failure and the attached request and response.

---

## Part 6: Rate-limit check (only if you are allowed)

This sends a burst of requests to one endpoint, so use it only on a test server and tell the team first.

Add to `myproject.properties`:

```properties
api.ratelimit.path=/orders
api.ratelimit.requests=30
```

Run:

```bash
mvn test -Dsite=myproject -DsuiteXmlFile=testng-suites/api-rate-limit.xml
```

It checks that the API answers with HTTP 429 when it is flooded, that the 429 has a `Retry-After` header, and that the API does not crash with server errors.

If no 429 ever appears, the endpoint may have no limit, or the limit is higher than 30. Raise `api.ratelimit.requests` a little.

---

## If something goes wrong

- **`Missing config key: url`**: the name after `-Dsite=` does not match the site from Step 2.
- **Everything is SKIPPED**: the settings from Step 9 are missing.
- **`Newman is not installed`**: redo Step 3 and open a new terminal.
- **Postman requests fail with "could not get response"**: the environment file is missing, or the base URL is wrong.
- **Login failed with 400 or 401**: check the username, the password, the login URL and the `tokenField` name.
- **`target/extent-reports` does not exist**: `BaseApiTest.java` is not the updated version (it must contain `@Listeners({TestListener.class})`).

---

## Safety

- Only test servers you are allowed to test, ideally a dev or staging server, never production.
- Do not commit tokens, passwords or environment files to Git.
- These security checks are a first-pass screen, not a full penetration test.
