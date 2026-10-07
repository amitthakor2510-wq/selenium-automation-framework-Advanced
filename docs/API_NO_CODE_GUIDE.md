# No-Code API Testing

Describe API requests in a text file (or point at a Swagger/OpenAPI URL), run **one command**, open **one HTML report**. No Java to write.

> This sits next to the existing Java API tests (`BookStoreApiTest`, `JsonPlaceholderApiTest`), the Postman/Newman runner, and the security / rate-limit suites in [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md). Nothing there changed.

Run every command in the folder that contains `pom.xml`.

---

## 1. Fastest start (pick one)

```bash
# A. You have a Swagger / OpenAPI URL or file  -> tests are generated for you
./Scripts/api-test.sh --openapi https://dev-api.company.com/v3/api-docs --token "$API_TOKEN"

# B. You write the requests yourself
./Scripts/api-test.sh --init                 # creates src/test/resources/apitests/my-api.yml
#   edit that file (baseUrl, auth, tests), then:
./Scripts/api-test.sh

# C. See it working first (public demo API, nothing to set up)
./Scripts/api-test.sh --example
```

When it finishes you get a pass/fail count in the terminal and these reports:

| Report | Where | Good for |
|---|---|---|
| One-page HTML (filter by failed/passed, request + response per test, secrets masked) | `target/api-report/index.html` | Sharing / emailing |
| Extent (framework standard) | `target/extent-reports/.../index.html` | Same report style as the UI tests |
| Allure (request + response attached to every call) | `./Scripts/api-test.sh --allure` | Deep debugging |

Exit code: `0` all passed, `1` something failed, `2` could not run (handy for CI).

Useful options: `--base-url`, `--token`, `--env staging`, `--tags smoke`, `--exclude-tags negative`, `--name "create user"`, `--file users`, `--var userId=5`, `--max-time 2000`, `--retry 2`, `--negative` (with `--openapi`), `--open`. See `./Scripts/api-test.sh --help`.

Equivalent plain Maven (what the script runs):

```bash
mvn test -Dsite=apitest -DsuiteXmlFile=testng-suites/api-spec.xml -Dapi.openapi=URL -Dapi.base.url=URL
```

---

## 2. What a spec file looks like

`src/test/resources/apitests/my-api.yml` (the generated template explains every option; a copy lives in `_template.yml`):

```yaml
name: Users API
baseUrl: https://dev-api.company.com
auth: { type: bearer, token: "${env:API_TOKEN}" }

tests:
  - name: Create user
    post: /users
    body: { name: "Asha", email: "${randomEmail}" }
    expect:
      status: 201
      maxTimeMs: 2000
      body: { id: notNull, name: Asha }
    extract: { userId: id }            # remember it for later requests
    cleanup: { method: DELETE, path: "/users/${userId}" }   # runs at the end of the run

  - name: Read it back
    get: /users/${userId}
    expect: { status: 200, body: { email: { contains: "@" } } }

  - name: Anonymous call is rejected
    get: /users/me
    auth: none
    expect: 401
```

Requests run **in file order, one at a time**, so a later request can use what an earlier one returned. If an early request fails, the ones that depend on its value show as *skipped* (with the reason) instead of piling up red failures.

Formats: `.yml/.yaml` (recommended), `.json` (same structure), `.csv` (one request per row: `name,method,path,status,body,headers,query,maxTimeMs,expectBody,extract,tags`). Files starting with `_` are ignored.

### Request keys

| Key | Meaning |
|---|---|
| `get` / `post` / `put` / `patch` / `delete` / `head` / `options: /path` | method + path (or `method:` and `path:`). A full `http(s)://` URL is used as-is |
| `headers`, `query` | maps; a list value repeats the parameter |
| `body` | JSON (numbers/booleans keep their type) |
| `bodyFile` | file whose text is the body; `${vars}` inside are replaced |
| `bodyText` + `contentType` | raw text / XML |
| `form` | `application/x-www-form-urlencoded` |
| `multipart` | `{ file: "file:path/to.pdf", note: "text" }` upload |
| `graphql` | `{ query, variables, operationName }` — POSTs to `/graphql`; any `errors` in the reply fails the test |
| `soapAction` | SOAP: sets `SOAPAction` + `text/xml`; use with `bodyText`/`bodyFile` |
| `auth` | per-case auth, or `none` |
| `retry`, `timeoutMs`, `insecure`, `followRedirects`, `baseUrl` | per-case overrides |
| `tags`, `skip` | `tags: [smoke]`; `skip: true` or `skip: "reason"` |
| `data` / `dataFile` | data-driven: one test per row (`dataFile: users.csv|json|yml`) |

### Expectations (`expect:`)

`expect: 200` is shorthand for `expect: { status: 200 }`. With no status given, **2xx** is expected.

| Key | Meaning |
|---|---|
| `status` | `201`, `[200, 201]`, `"2xx"`, `"200-299"`, `any` |
| `maxTimeMs` | response must be faster |
| `contentType` | Content-Type contains this text |
| `headers` | `{ Location: { startsWith: /users/ } }` |
| `body` | JSON path → value or operator (below) |
| `bodyContains`, `bodyNotContains`, `emptyBody` | raw text checks |
| `schema` | JSON Schema file (next to the spec, in the project, or on the classpath); `schemaInline` for an inline schema |
| `xml` | XML/SOAP: `Envelope.Body.AddResponse.AddResult: 5` (namespaces ignored), or a full XPath |

Every failed check of a request is reported together, not just the first.

**JSON paths**: `id`, `user.name`, `items[0].title`, `items[-1]`, `items[*].id` (list), `items[?id=2].title` (filter), `items.size()`, `$` (root), `['odd.key']`.

**Values**: plain value (equals; `5` equals `5.0`), or keywords `notNull`, `null`, `empty`, `notEmpty`, `absent`, `exists`, or an operator map — `eq ne gt gte lt lte between:[a,b] contains notContains startsWith endsWith equalsIgnoreCase matches(regex) type(string|number|integer|boolean|array|object|null) size sizeGt sizeGte sizeLt in notIn`. To compare with the literal text `notNull`, write `{ eq: notNull }`.

### Variables

`${name}` comes from `vars:`, a data row, an `extract:` of an earlier request, or `-Dapi.var.name=...` / `--var name=...`. Also: `${name:-default}`, `${env:API_TOKEN}`, `${prop:some.config.key}`, `${file:payloads/a.json}`, and generators `${uuid} ${timestamp} ${date} ${datetime} ${randomInt} ${randomInt:1:100} ${randomString:12} ${randomEmail}`.

`extract:` sources: a JSON path, `header:Name`, `status`, `body`, `regex:pattern`, `xml:path`.

### Auth

| `type` | Fields |
|---|---|
| `bearer` | `token` (optional `header`, `prefix`) |
| `basic` | `username`, `password` |
| `apiKey` | `name`, `value`, `in: header\|query` |
| `login` | `request: {method, path, body\|form}`, `tokenPath` — logs in **once**, reuses the token. Works for any login endpoint, including OAuth2 password / client-credentials token endpoints |
| `config` | uses the framework's existing `api.auth.*` keys (auto-refreshing OAuth2/JWT, see API_TESTING_GUIDE.md) |

`--token X` sets a bearer token for every case that has no `auth:` of its own. Keep real secrets in environment variables (`${env:...}`), not in files.

### Environments

```yaml
environments:
  staging: { baseUrl: "https://stg-api.company.com", vars: { userId: 5 }, headers: { X-Env: stg } }
```
Run with `--env staging`. `--base-url` overrides the URL from any file.

---

## 3. OpenAPI / Swagger

`--openapi <url|file>` (OpenAPI 3.x or Swagger 2.0, JSON or YAML) generates one test per operation:

- sample request from the document's examples / schemas (`readOnly` fields are left out)
- documented success status, required response fields checked with `exists`
- auth wired from `securitySchemes` (bearer / basic / apiKey) — set the matching `API_TOKEN` / `API_USER`+`API_PASSWORD` / `API_KEY` or pass `--token`
- POST first, DELETE last; a POST that returns `id` feeds the later `/{id}` calls (`${pets_id:-1}`)
- `--negative` adds "no credentials is rejected (401/403)" and "empty body is rejected (4xx)" tests

To keep and edit the result: `java -cp ... OpenApiSpecGenerator <url> out.yml`, or copy `target/generated-apitests/openapi-generated.yml` into `src/test/resources/apitests/` — it is a normal spec file.

---

## 4. Checklist coverage ("Essential Features for an API Testing Framework")

| # | Feature | Status |
|---|---|---|
| 1 | HTTP methods, file upload | ✅ GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS, multipart upload (download-to-disk is not covered) |
| 2 | Multi-environment config | ✅ `environments:` + `--env`, `--base-url`, `config/*.properties`, `-D` overrides |
| 3 | Authentication | ✅ bearer, basic, API key, login/OAuth2 token endpoints, existing refreshing providers |
| 4 | Fluent request builder | ✅ replaced by the spec file (the Java `ApiClient` is still there for hand-written tests) |
| 5 | Response validation | ✅ status, time, headers, JSON body, XML, raw text |
| 6 | JSON Schema | ✅ `schema:` / `schemaInline:` |
| 7 | Data-driven (JSON, CSV, Excel) | ✅ inline rows, CSV, JSON, YAML • ❌ Excel in the no-code runner (the Java `DataProvider` layer supports it) |
| 8 | Logging & reporting | ✅ HTML + Extent + Allure with request/response attachments, secrets masked |
| 9 | Retry | ✅ `retry:` per spec/case (network errors and 502/503/504 by default) |
| 10 | POJOs | ➖ not needed — requests/responses are JSON/XML text |
| 11 | Base test class | ✅ `BaseApiTest` + `ApiSpecRunner` |
| 12 | Negative testing | ✅ write `expect: 404`, or `--negative` for generated ones |
| 13 | Test data cleanup | ✅ `cleanup:` requests run at the end, newest first |
| 14 | Parallel execution | ❌ requests run sequentially on purpose (chaining). To go faster, split specs and run `--file` slices in separate processes |
| 15 | CI/CD | 🟡 one command + exit code + `pipeline-config.properties` entry; the GitHub/GitLab/Jenkins job files were **not** edited |
| 16 | File upload/download | 🟡 upload ✅, download ❌ |
| 17 | Response caching | ❌ not provided |
| 18 | Mock server | 🟡 the framework's WireMock switch (`-Dmock.enabled=true`) redirects the base URL; you supply the stubs |

Also still available for the same API: Postman collections (`api-postman.xml`), security checks (`api-security.xml`) and rate-limit checks (`api-rate-limit.xml`) — they read `config/<site>.properties`, so use `-Dsite=apitest` after adding `url=` and `api.security.*` keys there.

---

## 5. Troubleshooting

| Symptom | Fix |
|---|---|
| `No API tests found` (skipped) | Put a spec in `src/test/resources/apitests/` or pass `--spec` / `--openapi`; check `--tags/--name/--file` filters |
| `Environment variable API_TOKEN is not set` | `export API_TOKEN=...` or use `--token` |
| `Variable ${x} is not defined` | add it under `vars:`, `extract:` it earlier, or `--var x=...` |
| `Skipped - needs ${id} from an earlier request` | the request that creates it failed — fix that one first |
| `No base URL` | add `baseUrl:` or `--base-url` |
| HTTPS certificate errors | `defaults: { insecure: true }` (test servers only) |
| `body checks need a JSON response` | the server returned HTML/XML — check the URL/auth, or use `bodyContains` / `xml:` |

Only test servers you are allowed to test, ideally dev or staging. A spec with `cleanup:` and POST/DELETE calls changes data.
