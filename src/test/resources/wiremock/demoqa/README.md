# WireMock stubs — demoqa

Recorded stubs land here (`mappings/*.json`, `__files/*`) when you run:

```bash
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/api-tests.xml \
    -Dmock.enabled=true -Dmock.record=true
```

Nothing is checked in yet — with this folder empty, `-Dmock.enabled=true` is served entirely by the
built-in `DemoQaBookStoreFake` (hand-written seed data, not a recording).

Before committing recorded files, open them: they include the real server's response headers, and
you should not commit anything you wouldn't put in a public repo. See `docs/api-mocking.md`.
