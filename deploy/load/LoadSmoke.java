// The 10-terminal load smoke of spec §12 against a running stack (PRD S45 and S54, D136, A56). A single-file
// program: `java LoadSmoke.java ...` (JDK 25, java.net.http and virtual threads, no dependency). Run it through
// deploy/load/load-smoke.sh, which documents the options, the thresholds and the safety rules.

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class LoadSmoke {

    /** §12: p95 below this for local simple commands and reads. */
    static final long TARGET_MS = 500;
    /** Fixed seed: the catalog prices and every terminal's choices repeat from run to run. */
    static final long SEED = 45;
    static final UUID MANAGER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000302");
    static final UUID CASHIER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000304");
    static final String[] REPORTS = {"sales?groupBy=item", "taxes?", "receipts?groupBy=method", "sales?groupBy=hour",
            "margins?groupBy=item", "discounts?groupBy=origin"};

    /**
     * The plan: every endpoint the program calls. {@code measured} endpoints are timed and gated against
     * {@link #TARGET_MS}; the others are setup (seed, login) and only have to answer 2xx. {@code --dry-run} checks
     * that each one exists in the server's OpenAPI document.
     */
    enum Ep {
        // setup: authentication
        SESSION("GET", "/api/v1/auth/session", false),
        LOGIN("POST", "/api/v1/auth/login", false),
        ENROLL("POST", "/api/v1/auth/terminals/enroll", false),
        POS_LOGIN("POST", "/api/v1/auth/pos-login", false),
        // setup: the store
        STORES("POST", "/api/v1/backoffice/stores", false),
        AREAS("POST", "/api/v1/backoffice/areas", false),
        TABLES_BO("POST", "/api/v1/backoffice/tables", false),
        TERMINALS("POST", "/api/v1/backoffice/terminals", false),
        ENROLLMENT_CODE("POST", "/api/v1/backoffice/terminals/{id}/enrollment-code", false),
        EMPLOYEES("POST", "/api/v1/backoffice/employees", false),
        FAMILIES("POST", "/api/v1/backoffice/catalog/families", false),
        UNITS("POST", "/api/v1/backoffice/catalog/units", false),
        TAX_CATEGORIES("POST", "/api/v1/backoffice/catalog/tax-categories", false),
        TAX_RATES("POST", "/api/v1/backoffice/catalog/tax-categories/{id}/rates", false),
        PRICE_LISTS("POST", "/api/v1/backoffice/catalog/price-lists", false),
        PRICES("POST", "/api/v1/backoffice/catalog/price-lists/{id}/prices", false),
        ITEMS("POST", "/api/v1/backoffice/catalog/items", false),
        ITEM_LIST("GET", "/api/v1/backoffice/catalog/items", false),
        CATALOG_RELEASES("POST", "/api/v1/backoffice/catalog/releases", false),
        CATALOG_VALIDATE("POST", "/api/v1/backoffice/catalog/releases/{id}/validate", false),
        CATALOG_PUBLISH("POST", "/api/v1/backoffice/catalog/releases/{id}/publish", false),
        PRINTERS("POST", "/api/v1/backoffice/printers", false),
        PRINTER_MODE("PUT", "/api/v1/backoffice/printers/{id}/mode", false),
        ROUTES("PUT", "/api/v1/backoffice/production-routes", false),
        TERMINAL_PRINTERS("PUT", "/api/v1/backoffice/terminal-printers", false),
        SERIES("POST", "/api/v1/backoffice/fiscal/series", false),
        REGISTERS("POST", "/api/v1/backoffice/cash/registers", false),
        CUSTOMERS("POST", "/api/v1/backoffice/customers", false),
        CREDIT_LIMIT("POST", "/api/v1/backoffice/customers/{id}/credit-limit-changes", false),
        ADDRESSES("POST", "/api/v1/backoffice/customers/{id}/addresses", false),
        DISCOUNT_LIMITS("PUT", "/api/v1/backoffice/promotions/discount-limits/{id}", false),
        PROMOTIONS("POST", "/api/v1/backoffice/promotions", false),
        PROMOTION_SCOPE("PUT", "/api/v1/backoffice/promotions/{id}/scope", false),
        PROMOTION_STORES("PUT", "/api/v1/backoffice/promotions/{id}/stores", false),
        PROMOTION_RELEASES("POST", "/api/v1/backoffice/promotions/releases", false),
        PROMOTION_VALIDATE("POST", "/api/v1/backoffice/promotions/releases/{id}/validate", false),
        PROMOTION_PUBLISH("POST", "/api/v1/backoffice/promotions/releases/{id}/publish", false),
        ZONES("POST", "/api/v1/backoffice/delivery-zones", false),
        OPEN_SESSION("POST", "/api/v1/pos/cash/sessions", false),
        // measured: the POS cycle (R3 paths, plus R4: pricing at confirmation, discounts and offers, delivery)
        MAP("GET", "/api/v1/pos/tables", true),
        CATALOG("GET", "/api/v1/pos/catalog", true),
        PROMOTIONS_CURRENT("GET", "/api/v1/pos/promotions/current", true),
        OPEN_TABLE("POST", "/api/v1/pos/tables/{id}/open", true),
        OPEN_ORDER("POST", "/api/v1/pos/orders", true),
        DRAFT("POST", "/api/v1/pos/orders/{id}/lines", true),
        ORDER("GET", "/api/v1/pos/orders/{id}", true),
        CONFIRM("POST", "/api/v1/pos/orders/{id}/batches", true),
        SUBTOTAL("POST", "/api/v1/pos/orders/{id}/subtotal/print", true),
        SETTLEMENT("GET", "/api/v1/pos/orders/{id}/settlement", true),
        DISCOUNT("POST", "/api/v1/pos/orders/{id}/discounts", true),
        EQUAL_PARTS("POST", "/api/v1/pos/orders/{id}/partitions/equal", true),
        PARTITION("POST", "/api/v1/pos/orders/{id}/partitions", true),
        PARTITION_DOCUMENT("POST", "/api/v1/pos/partitions/{id}/documents", true),
        CASH("POST", "/api/v1/pos/payment-attempts", true),
        CREDIT_CHARGE("POST", "/api/v1/pos/orders/{id}/credit-charges", true),
        DELIVERY_BOARD("GET", "/api/v1/pos/deliveries", true),
        DISPATCH("POST", "/api/v1/pos/deliveries/{id}/dispatch", true),
        DELIVERED("POST", "/api/v1/pos/deliveries/{id}/delivered", true),
        CLOSE("POST", "/api/v1/pos/orders/{id}/close", true),
        // measured: the BackOffice watching the day
        REPORT_SALES("GET", "/api/v1/backoffice/reports/sales", true),
        REPORT_TAXES("GET", "/api/v1/backoffice/reports/taxes", true),
        REPORT_RECEIPTS("GET", "/api/v1/backoffice/reports/receipts", true),
        REPORT_MARGINS("GET", "/api/v1/backoffice/reports/margins", true),
        REPORT_DISCOUNTS("GET", "/api/v1/backoffice/reports/discounts", true);

        final String method;
        final String template;
        final boolean measured;

        Ep(String method, String template, boolean measured) {
            this.method = method;
            this.template = template;
            this.measured = measured;
        }

        /**
         * Gated against {@link #TARGET_MS}: every measured endpoint except those that issue a fiscal document
         * synchronously through the fiscal adapter port (close, a partition's document, a credit sale, D55/D97).
         * That port is an external call (D48, D54: a certified adapter talks to the tax authority's system), and
         * §12 / PRD S45 exclude external calls from the target. They are still measured and reported.
         */
        boolean gated() {
            return measured && this != CLOSE && this != PARTITION_DOCUMENT && this != CREDIT_CHARGE;
        }

        String label() {
            return method + " " + template.replace("/api/v1", "");
        }

        String path(Object... args) {
            String path = template;
            for (Object arg : args) {
                int open = path.indexOf('{');
                if (open < 0) {
                    throw new IllegalArgumentException("too many path arguments for " + template);
                }
                path = path.substring(0, open) + arg + path.substring(path.indexOf('}', open) + 1);
            }
            if (path.indexOf('{') >= 0) {
                throw new IllegalArgumentException("missing path argument for " + template);
            }
            return path;
        }

        static Ep report(String report) {
            return switch (report.substring(0, report.indexOf('?'))) {
                case "sales" -> REPORT_SALES;
                case "taxes" -> REPORT_TAXES;
                case "receipts" -> REPORT_RECEIPTS;
                case "margins" -> REPORT_MARGINS;
                case "discounts" -> REPORT_DISCOUNTS;
                default -> throw new IllegalArgumentException(report);
            };
        }
    }

    // ---- options ----

    record Options(String baseUrl, int terminals, int seconds, int items, boolean gate, Path reportDir,
                   Path caCert, Path apiDocs, String zone, String printerHost, int printerPort, String mode) {

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            String mode = "run";
            boolean gate = true;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--dry-run" -> mode = "dry-run";
                    case "--self-test" -> mode = "self-test";
                    case "--seed" -> { } // the run always seeds its own store first (kept for the S45 command line)
                    case "--no-gate" -> gate = false;
                    case "--base-url", "--terminals", "--seconds", "--items", "--report-dir", "--cacert", "--api-docs",
                         "--timezone", "--printer-host", "--printer-port" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException(arg + " needs a value");
                        }
                        values.put(arg, args[++i]);
                    }
                    default -> throw new IllegalArgumentException("unknown option " + arg);
                }
            }
            int terminals = Integer.parseInt(values.getOrDefault("--terminals", "10"));
            int seconds = Integer.parseInt(values.getOrDefault("--seconds", "60"));
            int items = Integer.parseInt(values.getOrDefault("--items", "300"));
            if (terminals < 1 || terminals > 40 || seconds < 1 || items < 5) {
                throw new IllegalArgumentException("--terminals 1..40, --seconds >= 1, --items >= 5");
            }
            return new Options(values.getOrDefault("--base-url", "https://restaurantpro.lan"), terminals, seconds,
                    items, gate, Path.of(values.getOrDefault("--report-dir", "reports")),
                    values.containsKey("--cacert") ? Path.of(values.get("--cacert")) : null,
                    values.containsKey("--api-docs") ? Path.of(values.get("--api-docs")) : null,
                    values.getOrDefault("--timezone", "UTC"), values.getOrDefault("--printer-host", "127.0.0.1"),
                    Integer.parseInt(values.getOrDefault("--printer-port", "9")), mode);
        }
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("load-smoke: " + e.getMessage());
            System.exit(2);
            return;
        }
        int exit;
        try {
            exit = switch (options.mode()) {
                case "self-test" -> SelfTest.run();
                case "dry-run" -> dryRun(options);
                default -> new LoadSmoke(options).run();
            };
        } catch (RequestFailed | IllegalStateException | IOException e) {
            System.err.println("load-smoke: " + e.getMessage());
            exit = 1;
        }
        System.exit(exit);
    }

    // ---- dry run: the plan against the server's OpenAPI document ----

    static int dryRun(Options options) throws Exception {
        String document;
        if (options.apiDocs() != null) {
            document = Files.readString(options.apiDocs());
            System.out.println("plan checked against " + options.apiDocs());
        } else {
            Http http = new Http(options);
            Tab office = new Tab("backoffice", http);
            adminLogin(office);
            document = office.send("GET", "/v3/api-docs", null, Map.of()).body();
            System.out.println("plan checked against " + options.baseUrl() + "/v3/api-docs");
        }
        List<String> missing = missingFromPlan(Json.parse(document));
        for (Ep ep : Ep.values()) {
            System.out.printf("  %-4s %-62s %s%n", ep.method, ep.template,
                    missing.contains(ep.method + " " + ep.template) ? "MISSING" : "ok");
        }
        if (!missing.isEmpty()) {
            System.err.println("load-smoke: " + missing.size() + " endpoint(s) of the plan are not in the API: "
                    + missing);
            return 1;
        }
        System.out.println("every endpoint of the plan (" + Ep.values().length + ") exists");
        return 0;
    }

    /** The plan entries the OpenAPI document lacks (path templates compared with their variables normalized). */
    static List<String> missingFromPlan(Object openApi) {
        Set<String> available = new HashSet<>();
        Object paths = Json.get(openApi, "paths");
        if (!(paths instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("not an OpenAPI document: no paths");
        }
        map.forEach((path, operations) -> ((Map<?, ?>) operations).keySet().forEach(method ->
                available.add(method.toString().toUpperCase(Locale.ROOT) + " " + normalize(path.toString()))));
        List<String> missing = new ArrayList<>();
        for (Ep ep : Ep.values()) {
            if (!available.contains(ep.method + " " + normalize(ep.template))) {
                missing.add(ep.method + " " + ep.template);
            }
        }
        return missing;
    }

    static String normalize(String template) {
        return template.replaceAll("\\{[^}]+}", "{}");
    }

    // ---- the run ----

    final Options options;
    final Http http;
    final String tag;
    final Map<Ep, List<Long>> latencies = new ConcurrentHashMap<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger ordersClosed = new AtomicInteger();
    final Map<String, AtomicInteger> variants = new ConcurrentHashMap<>();

    Tab office;
    UUID store;
    UUID zone;
    UUID courier;
    String managerCode;
    String managerPin;
    List<UUID> items = new ArrayList<>();
    long catalogVersion;
    Long promotionVersion;
    String businessDate;

    LoadSmoke(Options options) throws Exception {
        this.options = options;
        this.http = new Http(options);
        // a short, unique tag: every company-wide code the seed creates (items, employees, promotions) carries it
        this.tag = "L" + Long.toString(Instant.now().getEpochSecond(), 36).toUpperCase(Locale.ROOT);
    }

    record Terminal(int no, Tab tab, List<UUID> tables, UUID customer, UUID address, Random random) {
    }

    int run() throws Exception {
        System.out.println("load smoke " + tag + " against " + options.baseUrl() + ": " + options.terminals()
                + " terminals, " + options.seconds() + " s, " + options.items() + " items, gate "
                + (options.gate() ? "on" : "off"));
        List<Terminal> terminals;
        long seedStart = System.nanoTime();
        try {
            terminals = seed();
        } catch (RequestFailed | IllegalStateException | IOException failed) {
            System.err.println("load-smoke: the seed failed: " + failed.getMessage());
            return 1;
        }
        System.out.printf("seeded store LOAD-%s in %.1f s%n", tag, (System.nanoTime() - seedStart) / 1e9);

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        long began;
        long ended;
        int cycles = 0;
        int reports;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (Terminal terminal : terminals) {
                runs.add(pool.submit(() -> cycle(terminal, start)));
            }
            Future<Integer> reporter = pool.submit(() -> reporter(start, running));
            began = System.nanoTime();
            start.countDown();
            for (Future<Integer> run : runs) {
                cycles += run.get();
            }
            running.set(false);
            reports = reporter.get();
            ended = System.nanoTime();
        }
        Map<String, Object> report = report(Duration.ofNanos(ended - began), cycles, reports);
        Path file = write(report);
        System.out.println(text(report));
        System.out.println("report: " + file);
        List<String> misses = misses(rows(latencies));
        if (!errors.isEmpty()) {
            System.err.println("load-smoke: FAIL: " + errors.size() + " failed request(s); first: " + errors.getFirst());
            return 1;
        }
        if (options.gate() && !misses.isEmpty()) {
            System.err.println("load-smoke: FAIL: p95 not below " + TARGET_MS + " ms (§12): " + misses);
            return 1;
        }
        System.out.println(misses.isEmpty() ? "PASS: every gated operation has p95 < " + TARGET_MS + " ms"
                : "REPORTED (gate off): " + misses.size() + " operation(s) MISS: " + misses);
        return 0;
    }

    // ---- the seed ----

    private List<Terminal> seed() throws Exception {
        office = new Tab("backoffice", http);
        adminLogin(office);
        LocalTime cutoff = LocalTime.now(ZoneId.of(options.zone())).plusHours(12).truncatedTo(ChronoUnit.HOURS);
        store = id(office.ok(Ep.STORES, Json.obj("code", "LOAD-" + tag, "name", "Carga " + tag,
                "timezone", options.zone(), "businessDayCutoff", cutoff.toString())));
        UUID area = id(office.ok(Ep.AREAS, Json.obj("storeId", store, "code", "SALA", "name", "Sala")));
        UUID feeItem = publishCatalog(new Random(SEED));

        UUID kitchen = printer("COZ");
        UUID bar = printer("BAR");
        UUID counter = printer("BAL");
        office.ok(Ep.ROUTES, Json.obj("storeId", store, "routes", List.of(
                Json.obj("destinationCode", "COZINHA", "printerId", kitchen),
                Json.obj("destinationCode", "BAR", "printerId", bar))));
        office.ok(Ep.SERIES, Json.obj("storeId", store, "code", "FR", "firstNumber", 1));

        SecureRandom pins = new SecureRandom();
        managerPin = pin(pins);
        managerCode = tag + "GR";
        employee(managerCode, "Gerente", managerPin, MANAGER_ROLE);
        courier = employee(tag + "EST", "Estafeta", pin(pins), CASHIER_ROLE);

        int tables = Math.max(4 * options.terminals(), 40);
        List<UUID> tableIds = new ArrayList<>();
        for (int t = 1; t <= tables; t++) {
            tableIds.add(id(office.ok(Ep.TABLES_BO, Json.obj("storeId", store, "areaId", area, "code", "M" + t,
                    "seats", 4))));
        }
        List<UUID> terminalIds = new ArrayList<>();
        List<Object> assignments = new ArrayList<>();
        for (int i = 1; i <= options.terminals(); i++) {
            UUID terminal = id(office.ok(Ep.TERMINALS, Json.obj("storeId", store, "code", "POS" + i,
                    "name", "POS" + i)));
            terminalIds.add(terminal);
            assignments.add(Json.obj("terminalId", terminal, "printerId", counter));
        }
        office.ok(Ep.TERMINAL_PRINTERS, Json.obj("storeId", store, "assignments", assignments));

        // 15 % free: a 10 % discount is HALF_UP per unit (D101), so measured against the list (D106) it can be a
        // hair above 10 % (10.09 -> 9.08 is 10.01 %) and would then need an approver
        office.ok(Ep.DISCOUNT_LIMITS, Json.obj("freeLimitPercent", new BigDecimal("15"),
                "maxPercent", new BigDecimal("50")), store);
        publishPromotions();
        zone = id(office.ok(Ep.ZONES, Json.obj("storeId", store, "code", "Z1", "name", "Zona 1",
                "feeItemId", feeItem)));

        int perTerminal = tables / options.terminals();
        List<Terminal> terminals = new ArrayList<>();
        for (int i = 1; i <= options.terminals(); i++) {
            String pin = pin(pins);
            String code = tag + "E" + i;
            employee(code, "Empregado " + i, pin, CASHIER_ROLE);
            UUID register = id(office.ok(Ep.REGISTERS, Json.obj("storeId", store, "code", "CX" + i,
                    "name", "Caixa " + i)));
            String digits = String.format("%07d", Math.floorMod((tag + i).hashCode(), 10_000_000));
            UUID customer = id(office.ok(Ep.CUSTOMERS, Json.obj("name", "Cliente " + tag + " " + i,
                    "phone", "84" + digits, "taxId", "1" + digits + String.format("%02d", i))));
            office.ok(Ep.CREDIT_LIMIT, Json.obj("newLimit", new BigDecimal("1000000.00"), "reason", "Carga"),
                    customer);
            UUID address = id(office.ok(Ep.ADDRESSES, Json.obj("kind", "DELIVERY", "line1", "Rua da Carga " + i, "city", "Maputo"),
                    customer));
            Tab tab = posLogin(terminalIds.get(i - 1), code, pin, "pos" + i);
            Object session = tab.ok(Ep.OPEN_SESSION, Json.obj("registerId", register,
                    "openingFloat", new BigDecimal("1000.00")));
            businessDate = Json.str(session, "businessDate");
            terminals.add(new Terminal(i, tab, tableIds.subList((i - 1) * perTerminal, i * perTerminal), customer,
                    address, new Random(SEED * 1000 + i)));
        }
        Tab first = terminals.getFirst().tab();
        catalogVersion = Json.num(first.ok(Ep.CATALOG, null, Map.of(), "?storeId=" + store), "version").longValue();
        promotionVersion = Json.num(first.ok(Ep.PROMOTIONS_CURRENT, null, Map.of(), ""), "version").longValue();
        return terminals;
    }

    /** {@code items} items (10.00–399.99, seeded), two thirds at 16 % and one third at 23 % VAT, and a FEE item. */
    private UUID publishCatalog(Random random) throws Exception {
        UUID family = id(office.ok(Ep.FAMILIES, Json.obj("code", tag + "F", "name", "Carga " + tag)));
        UUID unit = id(office.ok(Ep.UNITS, Json.obj("code", tag + "U", "name", "Unidade " + tag)));
        Map<String, UUID> taxes = new LinkedHashMap<>();
        for (String rate : List.of("16", "23")) {
            UUID tax = id(office.ok(Ep.TAX_CATEGORIES, Json.obj("code", tag + "I" + rate, "name", "IVA " + rate)));
            office.ok(Ep.TAX_RATES, Json.obj("ratePercent", new BigDecimal(rate),
                    "effectiveFrom", "2000-01-01T00:00:00Z"), tax);
            taxes.put(rate, tax);
        }
        UUID priceList = id(office.ok(Ep.PRICE_LISTS, Json.obj("storeId", store, "name", "Sala")));
        for (int i = 1; i <= options.items(); i++) {
            UUID item = id(office.ok(Ep.ITEMS, Json.obj("code", tag + "I" + i, "name", "Prato " + i,
                    "familyId", family, "unitId", unit, "taxCategoryId", taxes.get(i % 3 == 0 ? "23" : "16"),
                    "productionDestination", i % 2 == 0 ? "BAR" : "COZINHA", "soldByWeight", false)));
            office.ok(Ep.PRICES, Json.obj("itemId", item,
                    "amount", BigDecimal.valueOf(1000 + random.nextInt(39_000), 2)), priceList);
            items.add(item);
        }
        UUID fee = id(office.ok(Ep.ITEMS, Json.obj("code", tag + "TAXA", "name", "Taxa de entrega",
                "familyId", family, "unitId", unit, "taxCategoryId", taxes.get("16"), "soldByWeight", false,
                "kind", "FEE")));
        office.ok(Ep.PRICES, Json.obj("itemId", fee, "amount", new BigDecimal("50.00")), priceList);
        priceTheOtherItems(priceList, fee);
        UUID release = id(office.ok(Ep.CATALOG_RELEASES, Json.obj("storeId", store)));
        office.ok(Ep.CATALOG_VALIDATE, null, release);
        office.ok(Ep.CATALOG_PUBLISH, Json.obj("note", "Carga " + tag), release);
        return fee;
    }

    /**
     * The catalog is company-wide and a store's release must price every active item, so the items other stores
     * (and earlier runs) created get a price in this store's list too. Store-local: nothing else changes.
     */
    private void priceTheOtherItems(UUID priceList, UUID fee) throws Exception {
        Set<String> ours = new HashSet<>();
        items.forEach(item -> ours.add(item.toString()));
        ours.add(fee.toString());
        for (int page = 0; ; page++) {
            Object listing = office.ok(Ep.ITEM_LIST, null, Map.of(), "?page=" + page + "&size=200");
            List<?> content = Json.list(listing, "content");
            for (Object item : content) {
                if (!ours.contains(Json.str(item, "id"))) {
                    office.ok(Ep.PRICES, Json.obj("itemId", id(item), "amount", new BigDecimal("10.00")), priceList);
                }
            }
            if (content.size() < 200) {
                return;
            }
        }
    }

    /** One automatic promotion (20 % off every fifth item, at any time) in a published release, so every
     * confirmation is priced by promotions (D103) with the version and expected gross the POS showed (D104). */
    private void publishPromotions() throws Exception {
        Object promotion = office.ok(Ep.PROMOTIONS, Json.obj("code", tag + "HH", "name", "Happy hour " + tag,
                "kind", "PERCENT_OFF", "percent", new BigDecimal("20"), "priority", 1));
        UUID id = id(promotion);
        List<UUID> scope = new ArrayList<>();
        for (int i = 0; i < items.size(); i += 5) {
            scope.add(items.get(i));
        }
        Object scoped = office.ok(Ep.PROMOTION_SCOPE, Json.obj("itemIds", scope,
                "version", Json.num(promotion, "version")), id);
        office.ok(Ep.PROMOTION_STORES, Json.obj("storeIds", List.of(store), "version", Json.num(scoped, "version")),
                id);
        UUID release = id(office.ok(Ep.PROMOTION_RELEASES, Json.obj("storeId", store)));
        office.ok(Ep.PROMOTION_VALIDATE, null, release);
        office.ok(Ep.PROMOTION_PUBLISH, null, release);
    }

    /** A printer in HOLD: its jobs stay QUEUED, so the load never prints, whatever the printer address. */
    private UUID printer(String code) throws Exception {
        Object printer = office.ok(Ep.PRINTERS, Json.obj("storeId", store, "code", code, "name", "Carga " + code,
                "host", options.printerHost(), "port", options.printerPort(), "codePage", "CP860"));
        office.ok(Ep.PRINTER_MODE, Json.obj("mode", "HOLD", "version", Json.num(printer, "version")), id(printer));
        return id(printer);
    }

    private UUID employee(String code, String name, String pin, UUID role) throws Exception {
        return id(office.ok(Ep.EMPLOYEES, Json.obj("code", code, "name", name, "pin", pin, "storeId", store,
                "roleId", role)));
    }

    /** A random 6-digit PIN for a throwaway load employee; never printed or written. */
    private static String pin(SecureRandom random) {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private Tab posLogin(UUID terminal, String code, String pin, String name) throws Exception {
        String enrollment = Json.str(office.ok(Ep.ENROLLMENT_CODE, null, terminal), "code");
        Tab pos = new Tab(name, http);
        pos.expect(Ep.SESSION, null, Map.of(), 401);
        String token = Json.str(pos.ok(Ep.ENROLL, Json.obj("code", enrollment)), "deviceToken");
        pos.ok(Ep.POS_LOGIN, Json.obj("employeeCode", code, "pin", pin), Map.of("X-Terminal-Token", token), "");
        return pos;
    }

    static void adminLogin(Tab office) throws Exception {
        String username = System.getenv().getOrDefault("LOAD_ADMIN_USERNAME", "admin");
        String passwordFile = System.getenv("LOAD_ADMIN_PASSWORD_FILE");
        if (passwordFile == null || passwordFile.isBlank()) {
            throw new IllegalStateException("set LOAD_ADMIN_PASSWORD_FILE to a file holding the administrator's "
                    + "password (never pass it on the command line)");
        }
        String password = Files.readString(Path.of(passwordFile)).strip();
        office.expect(Ep.SESSION, null, Map.of(), 401);
        Object session = office.ok(Ep.LOGIN, Json.obj("username", username, "password", password));
        if (Boolean.TRUE.equals(Json.get(session, "mustChangePassword"))) {
            throw new IllegalStateException("the administrator must change the initial password first (first "
                    + "login in the BackOffice)");
        }
    }

    // ---- the terminal loop ----

    private int cycle(Terminal terminal, CountDownLatch start) throws InterruptedException {
        start.await();
        long until = System.nanoTime() + Duration.ofSeconds(options.seconds()).toNanos();
        int cycles = 0;
        try {
            while (System.nanoTime() < until) {
                orderCycle(terminal, cycles);
                cycles++;
                ordersClosed.incrementAndGet();
            }
        } catch (RequestFailed failed) {
            errors.add("terminal " + terminal.no() + ": " + failed.getMessage());
        } catch (Exception e) {
            errors.add("terminal " + terminal.no() + ": " + e);
        }
        return cycles;
    }

    private void orderCycle(Terminal terminal, int cycle) throws Exception {
        Tab tab = terminal.tab();
        Random random = terminal.random();
        timed(tab, Ep.MAP, null, Map.of(), "?storeId=" + store);
        timed(tab, Ep.CATALOG, null, Map.of(), "?storeId=" + store);
        timed(tab, Ep.PROMOTIONS_CURRENT, null, Map.of(), "");
        boolean delivery = cycle % 5 == 4;
        UUID order;
        if (delivery) {
            order = id(timed(tab, Ep.OPEN_ORDER, Json.obj("mode", "DELIVERY", "delivery", Json.obj(
                    "customerId", terminal.customer(), "addressId", terminal.address(), "zoneId", zone)),
                    Map.of(), ""));
        } else {
            UUID table = terminal.tables().get(cycle % terminal.tables().size());
            order = id(timed(tab, Ep.OPEN_TABLE, Json.obj("guests", 1 + random.nextInt(4)), Map.of(), "", table));
        }
        List<UUID> roundOne = round(tab, random, order, 2 + random.nextInt(3));
        round(tab, random, order, 1 + random.nextInt(2));
        Object view = timed(tab, Ep.ORDER, null, Map.of(), "", order);
        if (!delivery) {
            timed(tab, Ep.SUBTOTAL, null, Map.of(), "", order);
        }
        switch (cycle % 5) {
            case 0 -> equalParts(tab, random, order);
            case 1 -> itemsPartition(tab, order, roundOne.getFirst());
            case 2 -> creditSale(terminal, order);
            case 3 -> discountAndOffer(tab, order, view);
            default -> deliver(tab, order);
        }
        Object closed = timed(tab, Ep.CLOSE, Json.obj("orderVersion", Json.num(settlement(tab, order),
                "orderVersion")), Map.of(), "", order);
        if (!"CLOSED".equals(Json.str(closed, "lifecycle"))) {
            throw new RequestFailed("close of " + order + " answered " + Json.write(closed));
        }
    }

    /**
     * Drafts {@code lines} seeded lines, reads the order, and confirms every draft (a delivery's automatic fee
     * too) with the catalog and promotion versions and the preview gross the POS showed (D104).
     */
    private List<UUID> round(Tab tab, Random random, UUID order, int lines) throws Exception {
        for (int i = 0; i < lines; i++) {
            timed(tab, Ep.DRAFT, Json.obj("itemId", items.get(random.nextInt(items.size())),
                    "quantity", BigDecimal.valueOf(1 + random.nextInt(3))), Map.of(), "", order);
        }
        Object view = timed(tab, Ep.ORDER, null, Map.of(), "", order);
        List<UUID> drafts = new ArrayList<>();
        BigDecimal expectedGross = BigDecimal.ZERO;
        for (Object line : Json.list(view, "lines")) {
            if ("DRAFT".equals(Json.str(line, "status"))) {
                drafts.add(id(line));
                expectedGross = expectedGross.add(Json.money(line, "gross"));
            }
        }
        timed(tab, Ep.CONFIRM, Json.obj("orderVersion", Json.num(view, "version"), "catalogVersion", catalogVersion,
                "lineIds", drafts, "promotionVersion", promotionVersion, "expectedGross", expectedGross),
                Map.of(), "", order);
        return drafts;
    }

    private Object settlement(Tab tab, UUID order) throws Exception {
        return timed(tab, Ep.SETTLEMENT, null, Map.of(), "", order);
    }

    /** The whole bill in 2 or 3 equal parts, each paid in cash. */
    private void equalParts(Tab tab, Random random, UUID order) throws Exception {
        variant("equal parts in cash");
        Object parts = timed(tab, Ep.EQUAL_PARTS, Json.obj("orderVersion", Json.num(settlement(tab, order),
                "orderVersion"), "parts", 2 + random.nextInt(2)), Map.of(), "", order);
        for (Object part : (List<?>) parts) {
            cash(tab, order, id(part), Json.money(part, "due"));
        }
    }

    /** The first line in a partition of its own, paid in cash, with its own document; the rest in cash. */
    private void itemsPartition(Tab tab, UUID order, UUID line) throws Exception {
        variant("items partition + document, rest in cash");
        Object view = timed(tab, Ep.ORDER, null, Map.of(), "", order);
        BigDecimal quantity = null;
        for (Object l : Json.list(view, "lines")) {
            if (line.toString().equals(Json.str(l, "id"))) {
                quantity = Json.num(l, "netQuantity");
            }
        }
        Object partition = timed(tab, Ep.PARTITION, Json.obj("orderVersion", Json.num(settlement(tab, order),
                "orderVersion"), "kind", "ITEMS", "label", "Cliente A",
                "lines", List.of(Json.obj("lineId", line, "quantity", quantity))), Map.of(), "", order);
        cash(tab, order, id(partition), Json.money(partition, "due"));
        timed(tab, Ep.PARTITION_DOCUMENT, Json.obj(), Map.of(), "", id(partition));
        cash(tab, order, null, Json.money(settlement(tab, order), "residualBalance"));
    }

    /** The whole bill on the terminal's customer's account. */
    private void creditSale(Terminal terminal, UUID order) throws Exception {
        variant("credit sale");
        Object charge = timed(terminal.tab(), Ep.CREDIT_CHARGE, Json.obj("customerId", terminal.customer(),
                "amount", Json.money(settlement(terminal.tab(), order), "due")), Map.of(), "", order);
        if (!"POSTED".equals(Json.str(charge, "status"))) {
            throw new RequestFailed("credit charge of " + order + " answered " + Json.write(charge));
        }
    }

    /**
     * R4 (D105, D106): a 10 % manual discount on a line no promotion priced (within the free limit, no approver),
     * then an offer of one unit of another line (always approved by the manager, D40 headers), then cash.
     */
    private void discountAndOffer(Tab tab, UUID order, Object view) throws Exception {
        variant("discount + offer, cash");
        List<Object> confirmed = new ArrayList<>();
        for (Object line : Json.list(view, "lines")) {
            if ("CONFIRMED".equals(Json.str(line, "status"))) {
                confirmed.add(line);
            }
        }
        Object discounted = confirmed.stream().filter(l -> Json.get(l, "promotionCode") == null).findFirst()
                .orElse(confirmed.getFirst());
        Map<String, String> approver = Map.of("X-Approver-Code", managerCode, "X-Approver-Pin", managerPin);
        boolean free = Json.get(discounted, "promotionCode") == null;
        timed(tab, Ep.DISCOUNT, Json.obj("orderVersion", Json.num(view, "version"), "kind", "DISCOUNT",
                "percent", new BigDecimal("10"), "reason", "Carga", "lines", List.of(Json.obj("lineId", id(discounted)))),
                free ? Map.of() : approver, "", order);
        Object offered = confirmed.stream().filter(l -> l != discounted).findFirst().orElse(null);
        if (offered != null) {
            Object fresh = timed(tab, Ep.ORDER, null, Map.of(), "", order);
            timed(tab, Ep.DISCOUNT, Json.obj("orderVersion", Json.num(fresh, "version"), "kind", "OFFER",
                    "reason", "Carga", "lines", List.of(Json.obj("lineId", id(offered), "quantity", BigDecimal.ONE))),
                    approver, "", order);
        }
        BigDecimal due = Json.money(settlement(tab, order), "due");
        if (due.signum() > 0) {
            cash(tab, order, null, due);
        }
    }

    /** R4 (D127, D129): dispatch to the courier (a delivery slip is queued), delivered, then cash on return. */
    private void deliver(Tab tab, UUID order) throws Exception {
        variant("delivery: dispatch, delivered, cash");
        Object board = timed(tab, Ep.DELIVERY_BOARD, null, Map.of(), "?status=PENDING");
        Object delivery = null;
        for (Object d : (List<?>) board) {
            if (order.toString().equals(Json.str(d, "orderId"))) {
                delivery = d;
            }
        }
        if (delivery == null) {
            throw new RequestFailed("delivery order " + order + " is not on the PENDING board");
        }
        Object dispatched = timed(tab, Ep.DISPATCH, Json.obj("version", Json.num(delivery, "version"),
                "courierEmployeeId", courier), Map.of(), "", order);
        timed(tab, Ep.DELIVERED, Json.obj("version", Json.num(dispatched, "version")), Map.of(), "", order);
        cash(tab, order, null, Json.money(settlement(tab, order), "due"));
    }

    private void cash(Tab tab, UUID order, UUID partition, BigDecimal amount) throws Exception {
        timed(tab, Ep.CASH, Json.obj("orderId", order, "partitionId", partition, "methodCode", "CASH",
                "amount", amount, "tendered", amount), Map.of(), "");
    }

    private void variant(String kind) {
        variants.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
    }

    /** A BackOffice tab reading 1-day reports (R3 sales, taxes, receipts; R4 margins, discounts) in turn. */
    private int reporter(CountDownLatch start, AtomicBoolean running) throws InterruptedException {
        start.await();
        int done = 0;
        try {
            while (running.get()) {
                String report = REPORTS[done % REPORTS.length];
                String query = report.substring(report.indexOf('?') + 1);
                timed(office, Ep.report(report), null, Map.of(), "?" + (query.isEmpty() ? "" : query + "&")
                        + "storeId=" + store + "&from=" + businessDate + "&to=" + businessDate);
                done++;
                Thread.sleep(250); // a person watching the day, not a second load generator
            }
        } catch (RequestFailed failed) {
            errors.add("reporter: " + failed.getMessage());
        } catch (Exception e) {
            errors.add("reporter: " + e);
        }
        return done;
    }

    private Object timed(Tab tab, Ep ep, Object body, Map<String, String> headers, String query, Object... args)
            throws Exception {
        long begin = System.nanoTime();
        Response response = tab.send(ep.method, ep.path(args) + query, body, headers);
        long elapsed = System.nanoTime() - begin;
        requests.incrementAndGet();
        if (response.status() < 200 || response.status() > 299) {
            throw new RequestFailed(ep.label() + " answered " + response.status() + " " + clip(response.body()));
        }
        latencies.computeIfAbsent(ep, k -> new CopyOnWriteArrayList<>()).add(elapsed);
        return response.json();
    }

    // ---- the report ----

    static Map<String, Map<String, Object>> rows(Map<Ep, List<Long>> latencies) {
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        latencies.forEach((ep, samples) -> {
            List<Long> sorted = samples.stream().sorted().toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", sorted.size());
            row.put("p50Ms", ms(sorted, 0.50));
            row.put("p95Ms", ms(sorted, 0.95));
            row.put("p99Ms", ms(sorted, 0.99));
            row.put("maxMs", ms(sorted, 1.0));
            row.put("targetP95Ms", TARGET_MS);
            row.put("gated", ep.gated());
            row.put("meets", meets(sorted));
            rows.put(ep.label(), row);
        });
        return rows;
    }

    /** §12: the p95 must be strictly below the target. */
    static boolean meets(List<Long> sortedNanos) {
        return ms(sortedNanos, 0.95).compareTo(BigDecimal.valueOf(TARGET_MS)) < 0;
    }

    /** Nearest-rank percentile, in milliseconds (3 decimals), as R3LoadSmoke. */
    static BigDecimal ms(List<Long> sorted, double percentile) {
        int rank = (int) Math.ceil(percentile * sorted.size());
        long nanos = sorted.get(Math.max(0, rank - 1));
        return BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(1_000_000), 3, RoundingMode.HALF_UP);
    }

    /** The gated operations whose p95 is not below the target. */
    static List<String> misses(Map<String, Map<String, Object>> rows) {
        return rows.entrySet().stream().filter(e -> (Boolean) e.getValue().get("gated"))
                .filter(e -> !(Boolean) e.getValue().get("meets")).map(Map.Entry::getKey).toList();
    }

    private Map<String, Object> report(Duration run, int cycles, int reports) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tag", tag);
        report.put("at", Instant.now().toString());
        report.put("baseUrl", options.baseUrl());
        report.put("terminals", options.terminals());
        report.put("seconds", options.seconds());
        report.put("items", options.items());
        report.put("seed", SEED);
        report.put("gate", options.gate());
        report.put("targetP95Ms", TARGET_MS);
        report.put("notGated", java.util.Arrays.stream(Ep.values()).filter(ep -> ep.measured && !ep.gated())
                .map(Ep::label).toList());
        report.put("runSeconds", BigDecimal.valueOf(run.toMillis()).divide(BigDecimal.valueOf(1000), 1,
                RoundingMode.HALF_UP));
        report.put("requests", requests.get());
        report.put("ordersClosed", ordersClosed.get());
        report.put("cycles", cycles);
        report.put("reports", reports);
        report.put("throughputRequestsPerSecond", BigDecimal.valueOf(requests.get() * 1000L)
                .divide(BigDecimal.valueOf(Math.max(1, run.toMillis())), 1, RoundingMode.HALF_UP));
        report.put("variants", new TreeMap<>(variants));
        report.put("client", System.getProperty("os.name") + ' ' + System.getProperty("os.arch") + ", "
                + Runtime.getRuntime().availableProcessors() + " CPUs, Java " + System.getProperty("java.version"));
        report.put("errors", List.copyOf(errors));
        Map<String, Map<String, Object>> rows = rows(latencies);
        report.put("operations", rows);
        report.put("misses", misses(rows));
        report.put("result", !errors.isEmpty() || options.gate() && !misses(rows).isEmpty() ? "FAIL" : "PASS");
        return report;
    }

    private Path write(Map<String, Object> report) throws IOException {
        Files.createDirectories(options.reportDir());
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path file = options.reportDir().resolve("load-" + stamp + ".json");
        Files.writeString(file, Json.write(report) + "\n");
        return file;
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> report) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%n  %-48s %6s %9s %9s %9s %9s  %s%n", "operation", "n", "p50 ms", "p95 ms",
                "p99 ms", "max ms", "p95 < " + TARGET_MS + " ms"));
        ((Map<String, Map<String, Object>>) report.get("operations")).forEach((op, row) -> out.append(String.format(
                "  %-48s %6d %9s %9s %9s %9s  %s%n", op, (Integer) row.get("n"), row.get("p50Ms"), row.get("p95Ms"),
                row.get("p99Ms"), row.get("maxMs"), ((Boolean) row.get("meets") ? "ok" : "MISS")
                        + ((Boolean) row.get("gated") ? "" : " (fiscal issue: reported, not gated)"))));
        out.append(String.format("%n  %s requests, %s orders closed in %s s (%s req/s); variants %s%n",
                report.get("requests"), report.get("ordersClosed"), report.get("runSeconds"),
                report.get("throughputRequestsPerSecond"), report.get("variants")));
        return out.toString();
    }

    static String clip(String body) {
        return body == null ? "" : body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    static UUID id(Object node) {
        return UUID.fromString(Json.str(node, "id"));
    }

    // ---- HTTP: one client, a cookie jar and CSRF per tab ----

    static final class RequestFailed extends Exception {
        RequestFailed(String message) {
            super(message);
        }
    }

    record Response(int status, String body) {
        Object json() {
            return body == null || body.isBlank() ? null : Json.parse(body);
        }
    }

    static final class Http {
        final HttpClient client;
        final String baseUrl;

        Http(Options options) throws Exception {
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .executor(Executors.newVirtualThreadPerTaskExecutor());
            if (options.caCert() != null) {
                builder.sslContext(trusting(options.caCert()));
            }
            this.client = builder.build();
            this.baseUrl = options.baseUrl().replaceAll("/+$", "");
        }

        /** Trusts the installation's own CA (Caddy's internal root, for example) in addition to nothing else. */
        private static SSLContext trusting(Path pem) throws Exception {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (InputStream in = Files.newInputStream(pem)) {
                int n = 0;
                for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    store.setCertificateEntry("ca" + n++, (X509Certificate) cert);
                }
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), new SecureRandom());
            return context;
        }
    }

    /** One browser tab: its own cookies (the session, XSRF-TOKEN), so each terminal has its own session. */
    static final class Tab {
        final String name;
        final Http http;
        final Map<String, String> cookies = new ConcurrentHashMap<>();

        Tab(String name, Http http) {
            this.name = name;
            this.http = http;
        }

        Response send(String method, String path, Object body, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(http.baseUrl + path))
                    .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (!cookies.isEmpty()) {
                request.header("Cookie", String.join("; ", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).toList()));
            }
            if (!"GET".equals(method)) {
                String xsrf = cookies.get("XSRF-TOKEN");
                if (xsrf != null) {
                    request.header("X-XSRF-TOKEN", xsrf);
                }
                request.header("Idempotency-Key", UUID.randomUUID().toString()); // one per command (spec §9)
            }
            headers.forEach(request::header);
            HttpResponse<String> response = http.client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                String cookie = pair.substring(0, eq).strip();
                String value = pair.substring(eq + 1).strip();
                if (value.isEmpty() || header.toLowerCase(Locale.ROOT).contains("max-age=0")) {
                    cookies.remove(cookie);
                } else {
                    cookies.put(cookie, value);
                }
            }
            return new Response(response.statusCode(), response.body());
        }

        Object expect(Ep ep, Object body, Map<String, String> headers, int status, Object... args) throws Exception {
            Response response = send(ep.method, ep.path(args), body, headers);
            if (response.status() != status) {
                throw new RequestFailed(name + ": " + ep.label() + " answered " + response.status() + " (expected "
                        + status + ") " + clip(response.body()));
            }
            return response.json();
        }

        /** A setup call that must answer 2xx. */
        Object ok(Ep ep, Object body, Object... args) throws Exception {
            return ok(ep, body, Map.of(), "", args);
        }

        Object ok(Ep ep, Object body, Map<String, String> headers, String query, Object... args) throws Exception {
            Response response = send(ep.method, ep.path(args) + query, body, headers);
            if (response.status() < 200 || response.status() > 299) {
                throw new RequestFailed(name + ": " + ep.label() + " answered " + response.status() + " "
                        + clip(response.body()));
            }
            return response.json();
        }
    }

    // ---- a minimal JSON reader and writer (objects as LinkedHashMap, numbers as BigDecimal) ----

    static final class Json {

        static Map<String, Object> obj(Object... keysAndValues) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < keysAndValues.length; i += 2) {
                map.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
            return map;
        }

        static Object get(Object node, String key) {
            return node instanceof Map<?, ?> map ? map.get(key) : null;
        }

        static String str(Object node, String key) {
            Object value = get(node, key);
            if (value == null) {
                throw new IllegalStateException("no '" + key + "' in " + clip(write(node)));
            }
            return value.toString();
        }

        static BigDecimal num(Object node, String key) {
            Object value = get(node, key);
            if (!(value instanceof BigDecimal number)) {
                throw new IllegalStateException("no number '" + key + "' in " + clip(write(node)));
            }
            return number;
        }

        static BigDecimal money(Object node, String key) {
            return num(get(node, key), "amount");
        }

        static List<?> list(Object node, String key) {
            Object value = get(node, key);
            return value instanceof List<?> list ? list : List.of();
        }

        static Object parse(String text) {
            Reader reader = new Reader(text);
            Object value = reader.value();
            reader.space();
            if (reader.at < text.length()) {
                throw new IllegalArgumentException("trailing characters at " + reader.at);
            }
            return value;
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out);
            return out.toString();
        }

        private static void write(Object value, StringBuilder out) {
            switch (value) {
                case null -> out.append("null");
                case Map<?, ?> map -> {
                    out.append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        string(e.getKey().toString(), out);
                        out.append(':');
                        write(e.getValue(), out);
                    }
                    out.append('}');
                }
                case Collection<?> list -> {
                    out.append('[');
                    boolean first = true;
                    for (Object item : list) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        write(item, out);
                    }
                    out.append(']');
                }
                case BigDecimal number -> out.append(number.toPlainString());
                case Number number -> out.append(number);
                case Boolean bool -> out.append(bool); // a Number (AtomicInteger too) prints its value above
                default -> string(value.toString(), out);
            }
        }

        private static void string(String s, StringBuilder out) {
            out.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }

        private static final class Reader {
            final String s;
            int at;

            Reader(String s) {
                this.s = s;
            }

            void space() {
                while (at < s.length() && Character.isWhitespace(s.charAt(at))) {
                    at++;
                }
            }

            Object value() {
                space();
                if (at >= s.length()) {
                    throw new IllegalArgumentException("unexpected end of JSON");
                }
                char c = s.charAt(at);
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            Map<String, Object> object() {
                Map<String, Object> map = new LinkedHashMap<>();
                at++;
                space();
                if (s.charAt(at) == '}') {
                    at++;
                    return map;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    map.put(key, value());
                    space();
                    if (s.charAt(at) == ',') {
                        at++;
                        continue;
                    }
                    expect('}');
                    return map;
                }
            }

            List<Object> array() {
                List<Object> list = new ArrayList<>();
                at++;
                space();
                if (s.charAt(at) == ']') {
                    at++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    space();
                    if (s.charAt(at) == ',') {
                        at++;
                        continue;
                    }
                    expect(']');
                    return list;
                }
            }

            String string() {
                expect('"');
                StringBuilder out = new StringBuilder();
                while (true) {
                    char c = s.charAt(at++);
                    if (c == '"') {
                        return out.toString();
                    }
                    if (c != '\\') {
                        out.append(c);
                        continue;
                    }
                    char e = s.charAt(at++);
                    switch (e) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                            at += 4;
                        }
                        default -> out.append(e);
                    }
                }
            }

            BigDecimal number() {
                int begin = at;
                while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0) {
                    at++;
                }
                if (begin == at) {
                    throw new IllegalArgumentException("unexpected '" + s.charAt(at) + "' at " + at);
                }
                return new BigDecimal(s.substring(begin, at));
            }

            Object literal(String word, Object value) {
                if (!s.startsWith(word, at)) {
                    throw new IllegalArgumentException("unexpected token at " + at);
                }
                at += word.length();
                return value;
            }

            void expect(char c) {
                if (at >= s.length() || s.charAt(at) != c) {
                    throw new IllegalArgumentException("expected '" + c + "' at " + at);
                }
                at++;
            }
        }
    }

    // ---- self-test: no server needed ----

    /**
     * Checks the parts a run depends on without a server: the percentile and the §12 gate (a p95 of exactly 500 ms
     * is a MISS), the plan check against an OpenAPI document (a removed endpoint is reported), the path templates,
     * the JSON round trip and the option parser. Each check is a positive and a negative case.
     */
    static final class SelfTest {
        static int failures;

        @SuppressWarnings("unchecked")
        static int run() {
            check("p95 nearest rank of 1..100 ms is 95 ms",
                    ms(nanos(1, 100), 0.95).compareTo(new BigDecimal("95.000")) == 0);
            check("p50 of a single sample is that sample", ms(List.of(7_000_000L), 0.5).compareTo(
                    new BigDecimal("7.000")) == 0);
            check("p95 of 499.999 ms meets the §12 target", meets(List.of(499_999_000L)));
            check("p95 of exactly 500 ms is a MISS (strictly below)", !meets(List.of(500_000_000L)));
            List<Long> tail = new ArrayList<>(nanos(1, 95));
            tail.addAll(List.of(600_000_000L, 600_000_000L, 600_000_000L, 600_000_000L, 600_000_000L));
            check("95 fast + 5 slow samples meet (p95 = 95th sample)", meets(tail));
            tail.add(600_000_000L);
            check("95 fast + 6 slow samples MISS", !meets(tail));
            Map<Ep, List<Long>> latencies = new LinkedHashMap<>();
            latencies.put(Ep.MAP, nanos(1, 100));
            latencies.put(Ep.CLOSE, List.of(700_000_000L));
            latencies.put(Ep.DISCOUNT, List.of(700_000_000L));
            check("misses() names exactly the slow gated operation", misses(rows(latencies)).equals(List.of(
                    Ep.DISCOUNT.label())));
            check("a slow fiscal-issuing operation is reported, not gated",
                    !(Boolean) rows(latencies).get(Ep.CLOSE.label()).get("gated")
                            && !(Boolean) rows(latencies).get(Ep.CLOSE.label()).get("meets"));
            check("exactly close, partition documents and credit charges are ungated measured endpoints",
                    java.util.Arrays.stream(Ep.values()).filter(ep -> ep.measured && !ep.gated()).toList()
                            .equals(List.of(Ep.PARTITION_DOCUMENT, Ep.CREDIT_CHARGE, Ep.CLOSE)));

            Map<String, Object> paths = new LinkedHashMap<>();
            for (Ep ep : Ep.values()) {
                ((Map<String, Object>) paths.computeIfAbsent(ep.template.replace("{id}", "{orderId}"),
                        k -> new LinkedHashMap<String, Object>())).put(ep.method.toLowerCase(Locale.ROOT), Map.of());
            }
            Map<String, Object> document = Json.obj("openapi", "3.1.0", "paths", paths);
            check("the plan is complete against a document that has every endpoint (variables renamed)",
                    missingFromPlan(Json.parse(Json.write(document))).isEmpty());
            paths.remove(Ep.DISPATCH.template.replace("{id}", "{orderId}"));
            ((Map<String, Object>) paths.get(Ep.CONFIRM.template.replace("{id}", "{orderId}"))).remove("post");
            check("a removed path and a removed method are reported",
                    missingFromPlan(document).equals(List.of("POST " + Ep.CONFIRM.template,
                            "POST " + Ep.DISPATCH.template)));
            check("a document without paths is refused", throwsIae(() -> missingFromPlan(Json.obj("x", 1))));

            check("path templates take their arguments in order",
                    Ep.DISPATCH.path("o-1").equals("/api/v1/pos/deliveries/o-1/dispatch"));
            check("a missing path argument is refused", throwsIae(() -> Ep.CLOSE.path()));
            check("a surplus path argument is refused", throwsIae(() -> Ep.MAP.path("x")));
            check("every measured endpoint is a /pos or /backoffice call", java.util.Arrays.stream(Ep.values())
                    .filter(ep -> ep.measured).allMatch(ep -> ep.template.startsWith("/api/v1/pos/")
                            || ep.template.startsWith("/api/v1/backoffice/")));
            check("every report of the reporter has its endpoint", java.util.Arrays.stream(REPORTS)
                    .allMatch(r -> Ep.report(r).measured));

            String json = "{\"a\":[1,2.50,{\"b\":null}],\"s\":\"x\\\"y\\u00e9\",\"t\":true,\"m\":{\"amount\":12.30}}";
            Object parsed = Json.parse(json);
            check("JSON round trip keeps numbers exactly", Json.write(parsed).equals(
                    "{\"a\":[1,2.50,{\"b\":null}],\"s\":\"x\\\"yé\",\"t\":true,\"m\":{\"amount\":12.30}}"));
            check("money() reads the amount", Json.money(parsed, "m").compareTo(new BigDecimal("12.30")) == 0);
            check("malformed JSON is refused", throwsIae(() -> Json.parse("{\"a\":1,}")));
            check("trailing JSON is refused", throwsIae(() -> Json.parse("{} x")));

            Options defaults = Options.parse(new String[0]);
            check("defaults: 10 terminals, 60 s, 300 items, gate on", defaults.terminals() == 10
                    && defaults.seconds() == 60 && defaults.items() == 300 && defaults.gate());
            check("--no-gate turns the gate off", !Options.parse(new String[] {"--no-gate"}).gate());
            check("an unknown option is refused", throwsIae(() -> Options.parse(new String[] {"--bogus"})));
            check("0 terminals is refused", throwsIae(() -> Options.parse(new String[] {"--terminals", "0"})));

            System.out.println(failures == 0 ? "self-test: all checks passed" : "self-test: " + failures + " FAILED");
            return failures == 0 ? 0 : 1;
        }

        static List<Long> nanos(int fromMs, int toMs) {
            List<Long> list = new ArrayList<>();
            for (int i = fromMs; i <= toMs; i++) {
                list.add(i * 1_000_000L);
            }
            return list;
        }

        static boolean throwsIae(Runnable action) {
            try {
                action.run();
                return false;
            } catch (IllegalArgumentException expected) {
                return true;
            }
        }

        static void check(String name, boolean passed) {
            System.out.println((passed ? "  ok    " : "  FAIL  ") + name);
            if (!passed) {
                failures++;
            }
        }
    }
}
