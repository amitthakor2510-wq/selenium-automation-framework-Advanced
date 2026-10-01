package com.automation.core.mock;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A small in-memory stand-in for DemoQA's Account + BookStore REST API, used by
 * {@link WireMockManager} as the low-priority fallback when {@code -Dmock.enabled=true}.
 *
 * <p>It exists because these endpoints are stateful: the account created by call 1 has to be the
 * one call 6 adds a book to and call 7 reads back, and the IDs are new every run. A recorded stub
 * can only replay one fixed answer, so it cannot stand in for that chain. This class keeps real
 * state (accounts, tokens, each account's book collection) and answers the way the live API does.
 *
 * <p>The status codes, error codes and messages here are the ones {@code BookStoreApiTest} and
 * {@code BookStoreApiNegativeTest} already assert — those two classes are this fake's contract.
 * The book catalogue is hand-written seed data (eight books, real ISBNs), NOT a recording; record
 * the real one with {@code -Dmock.record=true} when exact fidelity matters — a recorded catalogue
 * takes priority over this one automatically.
 *
 * <p>Thread-safe: every request is handled under one lock, so parallel test classes can each
 * create their own account against the same server.
 */
public class DemoQaBookStoreFake implements ResponseDefinitionTransformerV2 {

    /** Name a stub uses to route its response through this fake. */
    public static final String NAME = "demoqa-bookstore-fake";

    private static final String JSON = "application/json";
    private static final String NOT_AUTHORIZED = "{\"code\":\"1200\",\"message\":\"User not authorized!\"}";

    private final Object lock = new Object();
    private final Map<String, Account> accountsById = new HashMap<>();
    private final Map<String, Account> accountsByName = new HashMap<>();
    private final Map<String, String> userIdByToken = new HashMap<>();
    private final Map<String, Map<String, Object>> catalogue = new LinkedHashMap<>();

    public DemoQaBookStoreFake() {
        seedCatalogue();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    /** Forgets every account and token; the catalogue is untouched. */
    public void reset() {
        synchronized (lock) {
            accountsById.clear();
            accountsByName.clear();
            userIdByToken.clear();
        }
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        Request request = serveEvent.getRequest();
        String url = request.getUrl();
        int queryStart = url.indexOf('?');
        String path = queryStart < 0 ? url : url.substring(0, queryStart);
        String method = request.getMethod().getName();

        synchronized (lock) {
            try {
                return route(request, method, path);
            } catch (RuntimeException e) {
                return respond(400, "{\"code\":\"1200\",\"message\":\"Bad request: " + e.getClass().getSimpleName() + "\"}");
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private ResponseDefinition route(Request request, String method, String path) {
        if ("POST".equals(method) && "/Account/v1/User".equals(path)) {
            return createUser(request);
        }
        if ("POST".equals(method) && "/Account/v1/GenerateToken".equals(path)) {
            return generateToken(request);
        }
        if ("POST".equals(method) && "/Account/v1/Authorized".equals(path)) {
            return authorized(request);
        }
        if ("POST".equals(method) && "/Account/v1/Login".equals(path)) {
            return login(request);
        }
        if (path.startsWith("/Account/v1/User/")) {
            String userId = path.substring("/Account/v1/User/".length());
            if ("GET".equals(method)) {
                return getUser(request, userId);
            }
            if ("DELETE".equals(method)) {
                return deleteUser(request, userId);
            }
        }
        if ("GET".equals(method) && "/BookStore/v1/Books".equals(path)) {
            return respond(200, Json.write(Map.of("books", new ArrayList<>(catalogue.values()))));
        }
        if ("GET".equals(method) && "/BookStore/v1/Book".equals(path)) {
            return getBook(request);
        }
        if ("POST".equals(method) && "/BookStore/v1/Books".equals(path)) {
            return addBooks(request);
        }
        if ("DELETE".equals(method) && "/BookStore/v1/Book".equals(path)) {
            return deleteBook(request);
        }
        return respond(404, "{\"code\":\"404\",\"message\":\"No such endpoint in DemoQaBookStoreFake: "
            + method + " " + path + "\"}");
    }

    // ------------------------------------------------------------------ account endpoints

    private ResponseDefinition createUser(Request request) {
        Map<?, ?> body = readBody(request);
        String username = text(body, "userName");
        String password = text(body, "password");
        if (username.isEmpty() || password.isEmpty()) {
            return respond(400, "{\"code\":\"1200\",\"message\":\"UserName and Password required.\"}");
        }
        if (accountsByName.containsKey(username)) {
            return respond(406, "{\"code\":\"1204\",\"message\":\"User exists!\"}");
        }
        Account account = new Account(UUID.randomUUID().toString(), username, password);
        accountsById.put(account.userId, account);
        accountsByName.put(username, account);

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("userID", account.userId);
        created.put("username", username);
        created.put("books", new ArrayList<>());
        return respond(201, Json.write(created));
    }

    private ResponseDefinition generateToken(Request request) {
        Map<?, ?> body = readBody(request);
        Account account = authenticate(text(body, "userName"), text(body, "password"));
        if (account == null) {
            // Literal JSON, not Json.write(): WireMock's writer drops null-valued keys, but the
            // live API sends an explicit "token": null and a client may well check for it.
            return respond(200, "{\"token\":null,\"expires\":null,\"status\":\"Failed\","
                + "\"result\":\"User authorization failed.\"}");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", issueToken(account));
        result.put("expires", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        result.put("status", "Success");
        result.put("result", "User authorized successfully.");
        return respond(200, Json.write(result));
    }

    private ResponseDefinition authorized(Request request) {
        Map<?, ?> body = readBody(request);
        Account account = authenticate(text(body, "userName"), text(body, "password"));
        if (account == null && !accountsByName.containsKey(text(body, "userName"))) {
            return respond(404, "{\"code\":\"1207\",\"message\":\"User not found!\"}");
        }
        return respond(200, String.valueOf(account != null));
    }

    private ResponseDefinition login(Request request) {
        Map<?, ?> body = readBody(request);
        Account account = authenticate(text(body, "userName"), text(body, "password"));
        if (account == null) {
            return respond(404, "{\"code\":\"1207\",\"message\":\"User not found!\"}");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", account.userId);
        result.put("username", account.username);
        result.put("password", account.password);
        result.put("token", issueToken(account));
        result.put("expires", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        result.put("created_date", account.created.toString());
        result.put("isActive", false);
        return respond(200, Json.write(result));
    }

    private ResponseDefinition getUser(Request request, String userId) {
        Account account = authorizedAccount(request, userId);
        if (account == null) {
            return respond(401, NOT_AUTHORIZED);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", account.userId);
        result.put("username", account.username);
        result.put("books", booksOf(account));
        return respond(200, Json.write(result));
    }

    private ResponseDefinition deleteUser(Request request, String userId) {
        Account account = authorizedAccount(request, userId);
        if (account == null) {
            return respond(401, NOT_AUTHORIZED);
        }
        accountsById.remove(account.userId);
        accountsByName.remove(account.username);
        userIdByToken.values().removeIf(account.userId::equals);
        return emptyResponse(204);
    }

    // ------------------------------------------------------------------ book endpoints

    private ResponseDefinition getBook(Request request) {
        String isbn = request.queryParameter("ISBN").isPresent()
            ? request.queryParameter("ISBN").firstValue() : "";
        Map<String, Object> book = catalogue.get(isbn);
        return book == null ? respond(400, notInCatalogue()) : respond(200, Json.write(book));
    }

    private ResponseDefinition addBooks(Request request) {
        Map<?, ?> body = readBody(request);
        Account account = authorizedAccount(request, text(body, "userId"));
        if (account == null) {
            return respond(401, NOT_AUTHORIZED);
        }
        List<String> requested = new ArrayList<>();
        Object collection = body.get("collectionOfIsbns");
        if (collection instanceof List<?>) {
            for (Object entry : (List<?>) collection) {
                if (entry instanceof Map<?, ?>) {
                    requested.add(text((Map<?, ?>) entry, "isbn"));
                }
            }
        }
        for (String isbn : requested) {
            if (!catalogue.containsKey(isbn)) {
                return respond(400, notInCatalogue());
            }
            if (account.isbns.contains(isbn)) {
                return respond(400,
                    "{\"code\":\"1215\",\"message\":\"ISBN already present in the User's Collection!\"}");
            }
        }
        account.isbns.addAll(requested);

        List<Map<String, Object>> added = new ArrayList<>();
        for (String isbn : requested) {
            added.add(Map.of("isbn", isbn));
        }
        return respond(201, Json.write(Map.of("books", added)));
    }

    private ResponseDefinition deleteBook(Request request) {
        Map<?, ?> body = readBody(request);
        Account account = authorizedAccount(request, text(body, "userId"));
        if (account == null) {
            return respond(401, NOT_AUTHORIZED);
        }
        if (!account.isbns.remove(text(body, "isbn"))) {
            return respond(400,
                "{\"code\":\"1206\",\"message\":\"ISBN supplied is not available in User's Collection!\"}");
        }
        return emptyResponse(204);
    }

    // ------------------------------------------------------------------ helpers

    private Account authenticate(String username, String password) {
        Account account = accountsByName.get(username);
        return account != null && account.password.equals(password) ? account : null;
    }

    private String issueToken(Account account) {
        String token = "fake." + UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
        userIdByToken.put(token, account.userId);
        return token;
    }

    /** The account behind the request's Bearer token — only if that token belongs to {@code userId}. */
    private Account authorizedAccount(Request request, String userId) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String owner = userIdByToken.get(header.substring("Bearer ".length()).trim());
        if (owner == null || !owner.equals(userId)) {
            return null;
        }
        return accountsById.get(owner);
    }

    private List<Map<String, Object>> booksOf(Account account) {
        List<Map<String, Object>> books = new ArrayList<>();
        for (String isbn : account.isbns) {
            Map<String, Object> book = catalogue.get(isbn);
            if (book != null) {
                books.add(book);
            }
        }
        return books;
    }

    private static Map<?, ?> readBody(Request request) {
        String raw = request.getBodyAsString();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return Json.read(raw, Map.class);
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private static String notInCatalogue() {
        return "{\"code\":\"1205\",\"message\":\"ISBN supplied is not available in the Books Collection!\"}";
    }

    private static ResponseDefinition respond(int status, String body) {
        return build(status, body);
    }

    private static ResponseDefinition emptyResponse(int status) {
        return build(status, null);
    }

    private static ResponseDefinition build(int status, String body) {
        ResponseDefinitionBuilder builder = ResponseDefinitionBuilder.responseDefinition().withStatus(status);
        if (body != null) {
            builder.withHeader("Content-Type", JSON).withBody(body);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ seed data

    private void seedCatalogue() {
        book("9781449325862", "Git Pocket Guide", "A Working Introduction", "Richard E. Silverman",
            "2020-06-04T08:48:39.000Z", "O'Reilly Media", 234,
            "http://chimera.labs.oreilly.com/books/1230000000561/index.html");
        book("9781449331818", "Learning JavaScript Design Patterns",
            "A JavaScript and jQuery Developer's Guide", "Addy Osmani",
            "2020-06-04T09:11:40.000Z", "O'Reilly Media", 254,
            "http://www.addyosmani.com/resources/essentialjsdesignpatterns/book/");
        book("9781449337711", "Designing Evolvable Web APIs with ASP.NET",
            "Harnessing the Power of the Web", "Glenn Block, et al.",
            "2020-06-04T09:12:43.000Z", "O'Reilly Media", 238,
            "http://chimera.labs.oreilly.com/books/1234000001708/index.html");
        book("9781449365035", "Speaking JavaScript", "An In-Depth Guide for Programmers",
            "Axel Rauschmayer", "2014-02-01T00:00:00.000Z", "O'Reilly Media", 460,
            "http://speakingjs.com/");
        book("9781491904244", "You Don't Know JS", "ES6 & Beyond", "Kyle Simpson",
            "2015-12-27T00:00:00.000Z", "O'Reilly Media", 278,
            "https://github.com/getify/You-Dont-Know-JS/tree/master/es6%20&%20beyond");
        book("9781491950296", "Programming JavaScript Applications",
            "Robust Web Architecture with Node, HTML5, and Modern JS Libraries", "Eric Elliott",
            "2014-07-01T00:00:00.000Z", "O'Reilly Media", 254,
            "http://chimera.labs.oreilly.com/books/1234000000262/index.html");
        book("9781593275846", "Eloquent JavaScript, Second Edition", "A Modern Introduction to Programming",
            "Marijn Haverbeke", "2014-12-14T00:00:00.000Z", "No Starch Press", 472,
            "http://eloquentjavascript.net/");
        book("9781593277574", "Understanding ECMAScript 6", "The Definitive Guide for JavaScript Developers",
            "Nicholas C. Zakas", "2016-09-03T00:00:00.000Z", "No Starch Press", 352,
            "https://leanpub.com/understandinges6/read");
    }

    private void book(String isbn, String title, String subTitle, String author, String publishDate,
                      String publisher, int pages, String website) {
        Map<String, Object> book = new LinkedHashMap<>();
        book.put("isbn", isbn);
        book.put("title", title);
        book.put("subTitle", subTitle);
        book.put("author", author);
        book.put("publish_date", publishDate);
        book.put("publisher", publisher);
        book.put("pages", pages);
        book.put("description", "Seed data from DemoQaBookStoreFake — not a recording of the live API.");
        book.put("website", website);
        catalogue.put(isbn, book);
    }

    /** One registered account and its book collection. */
    private static final class Account {
        private final String userId;
        private final String username;
        private final String password;
        private final Instant created = Instant.now();
        private final Set<String> isbns = new LinkedHashSet<>();

        private Account(String userId, String username, String password) {
            this.userId = userId;
            this.username = username;
            this.password = password;
        }
    }
}
