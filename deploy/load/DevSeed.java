// Local development seed: fills a fresh dev installation (backend/run-local.sh) with a ready-to-use store through
// the HTTP API only, so every business rule applies. DEV ONLY: fixed, printed credentials. Run it through
// backend/dev-seed.sh. It shares LoadSmoke's HTTP client, per-tab cookies and CSRF, and JSON (same directory: the
// JDK 25 source launcher compiles LoadSmoke.java on demand, JEP 458).

import java.io.Console;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DevSeed {

    static final String STORE = "DEV";
    static final String ZONE = "Africa/Maputo";
    static final String CUTOFF = "05:00";
    static final UUID MANAGER_ROLE = LoadSmoke.MANAGER_ROLE;
    static final UUID WAITER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000303");
    static final UUID CASHIER_ROLE = LoadSmoke.CASHIER_ROLE;
    static final UUID STOCK_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000306");
    static final String BO = "/api/v1/backoffice";
    static final String POS = "/api/v1/pos";
    static final String CATALOG = BO + "/catalog";
    static final String INV = BO + "/inventory";

    // ---- the fixed dev credentials, printed by design ----

    record Person(String code, String name, String pin, UUID role, String job) {
    }

    static final Person GERENTE = new Person("GERENTE", "Gerente", "1111", MANAGER_ROLE, "approver");
    static final Person CAIXA = new Person("CAIXA", "Caixa", "2222", CASHIER_ROLE, "cashier");
    static final Person EMPREGADO = new Person("EMPREGADO", "Empregado", "3333", WAITER_ROLE, "waiter");
    static final Person ESTAFETA = new Person("ESTAFETA", "Estafeta", "4444", WAITER_ROLE, "courier");
    static final List<Person> PEOPLE = List.of(GERENTE, CAIXA, EMPREGADO, ESTAFETA);

    /** A BackOffice account; the policy needs at least 12 characters (PasswordPolicy), so "Stock-2026!" has two "!". */
    record Account(String username, String password, UUID role, Person employee, String job) {
    }

    static final Account MANAGER = new Account("gerente", "Gerente-2026!", MANAGER_ROLE, GERENTE,
            "manager, installation-wide");
    static final Account STOCK = new Account("stock", "Stock-2026!!", STOCK_ROLE, null, "stock and purchasing");
    static final List<Account> ACCOUNTS = List.of(MANAGER, STOCK);

    // ---- the catalog: code, name, family, destination, gross price (MZN, 16 % VAT) ----

    record Dish(String code, String name, String family, String destination, String price) {
    }

    static final List<String[]> FAMILIES = List.of(new String[] {"BEBIDAS", "Bebidas"},
            new String[] {"PRATOS", "Pratos"}, new String[] {"SOBREMESAS", "Sobremesas"},
            new String[] {"CAFES", "Cafés"}, new String[] {"TAXAS", "Taxas"});
    static final List<Dish> DISHES = List.of(
            new Dish("AGUA", "Água 50cl", "BEBIDAS", "BAR", "60.00"),
            new Dish("REFRIGERANTE", "Refrigerante lata", "BEBIDAS", "BAR", "80.00"),
            new Dish("CERVEJA", "Cerveja 2M", "BEBIDAS", "BAR", "100.00"),
            new Dish("SUMO", "Sumo de manga", "BEBIDAS", "BAR", "120.00"),
            new Dish("BATIDO", "Batido de manga", "BEBIDAS", "BAR", "150.00"),
            new Dish("FRANGO", "Frango grelhado", "PRATOS", "COZINHA", "450.00"),
            new Dish("BIFE", "Bife com batata frita", "PRATOS", "COZINHA", "550.00"),
            new Dish("PEIXE", "Peixe grelhado com arroz", "PRATOS", "COZINHA", "600.00"),
            new Dish("CARIL", "Caril de frango", "PRATOS", "COZINHA", "420.00"),
            new Dish("OMELETE", "Omelete com batata", "PRATOS", "COZINHA", "280.00"),
            new Dish("PUDIM", "Pudim", "SOBREMESAS", "COZINHA", "150.00"),
            new Dish("MOUSSE", "Mousse de chocolate", "SOBREMESAS", "COZINHA", "170.00"),
            new Dish("SALADA_FRUTA", "Salada de fruta", "SOBREMESAS", "COZINHA", "140.00"),
            new Dish("ARROZ_DOCE", "Arroz doce", "SOBREMESAS", "COZINHA", "130.00"),
            new Dish("EXPRESSO", "Café expresso", "CAFES", "BAR", "60.00"),
            new Dish("DUPLO", "Café duplo", "CAFES", "BAR", "90.00"),
            new Dish("GALAO", "Galão", "CAFES", "BAR", "100.00"),
            new Dish("CAPPUCCINO", "Cappuccino", "CAFES", "BAR", "110.00"));
    static final String MENU = "MENU_DIA";
    static final String FEE = "TAXA_ENTREGA";

    // ---- inventory: stock items (code, name, base unit) and recipes (stock item, quantity, unit) ----

    static final List<String[]> STOCK_ITEMS = List.of(new String[] {"FRANGO", "Frango", "kg"},
            new String[] {"CARNE", "Carne de vaca", "kg"}, new String[] {"PEIXE", "Peixe", "kg"},
            new String[] {"ARROZ", "Arroz", "kg"}, new String[] {"BATATA", "Batata", "kg"},
            new String[] {"OVO", "Ovo", "un"}, new String[] {"LEITE", "Leite", "l"},
            new String[] {"MANGA", "Manga", "kg"}, new String[] {"CAFE_GRAO", "Café em grão", "kg"},
            new String[] {"AGUA_50", "Água 50cl", "un"}, new String[] {"REFRIGERANTE_LATA", "Refrigerante lata", "un"},
            new String[] {"CERVEJA_2M", "Cerveja 2M", "un"}, new String[] {"CAIXA_TAKEAWAY", "Caixa take-away", "un"});
    static final Map<String, List<String[]>> RECIPES = new LinkedHashMap<>();

    static {
        recipe("AGUA", "AGUA_50 1 un");
        recipe("REFRIGERANTE", "REFRIGERANTE_LATA 1 un");
        recipe("CERVEJA", "CERVEJA_2M 1 un");
        recipe("SUMO", "MANGA 300 g");
        recipe("BATIDO", "MANGA 200 g", "LEITE 200 ml");
        recipe("FRANGO", "FRANGO 400 g", "BATATA 250 g");
        recipe("BIFE", "CARNE 250 g", "BATATA 300 g", "OVO 1 un");
        recipe("PEIXE", "PEIXE 350 g", "ARROZ 150 g");
        recipe("CARIL", "FRANGO 300 g", "ARROZ 150 g");
        recipe("OMELETE", "OVO 3 un", "BATATA 150 g");
        recipe("PUDIM", "OVO 2 un", "LEITE 200 ml");
        recipe("MOUSSE", "OVO 2 un", "LEITE 100 ml");
        recipe("SALADA_FRUTA", "MANGA 200 g");
        recipe("ARROZ_DOCE", "ARROZ 50 g", "LEITE 250 ml");
        recipe("EXPRESSO", "CAFE_GRAO 8 g");
        recipe("DUPLO", "CAFE_GRAO 16 g");
        recipe("GALAO", "CAFE_GRAO 8 g", "LEITE 200 ml");
        recipe("CAPPUCCINO", "CAFE_GRAO 8 g", "LEITE 150 ml");
    }

    /** Opening counts: stock item, quantity in its base unit, unit cost (MZN). */
    static final List<String[]> KITCHEN_OPENING = List.of(new String[] {"FRANGO", "20", "350.00"},
            new String[] {"CARNE", "15", "600.00"}, new String[] {"PEIXE", "10", "500.00"},
            new String[] {"ARROZ", "25", "90.00"}, new String[] {"BATATA", "30", "60.00"},
            new String[] {"OVO", "180", "12.00"}, new String[] {"LEITE", "20", "80.00"},
            new String[] {"MANGA", "10", "70.00"}, new String[] {"CAIXA_TAKEAWAY", "100", "15.00"});
    static final List<String[]> BAR_OPENING = List.of(new String[] {"AGUA_50", "96", "20.00"},
            new String[] {"REFRIGERANTE_LATA", "96", "35.00"}, new String[] {"CERVEJA_2M", "120", "45.00"},
            new String[] {"MANGA", "15", "70.00"}, new String[] {"LEITE", "20", "80.00"},
            new String[] {"CAFE_GRAO", "3", "1200.00"});

    static void recipe(String item, String... ingredients) {
        RECIPES.put(item, java.util.Arrays.stream(ingredients).map(i -> i.split(" ")).toList());
    }

    // ---- the run ----

    final String baseUrl;
    final Path outDir;
    final LoadSmoke.Http http;
    LoadSmoke.Tab admin;
    UUID store;
    boolean storeCreated;
    final Map<String, UUID> terminals = new LinkedHashMap<>();
    final Map<String, String> tokens = new LinkedHashMap<>();
    final Map<String, UUID> employees = new LinkedHashMap<>();
    final Map<String, UUID> items = new LinkedHashMap<>();
    final Map<String, UUID> families = new LinkedHashMap<>();
    final Map<String, UUID> stock = new LinkedHashMap<>();
    final Map<String, UUID> tables = new LinkedHashMap<>();
    UUID register;
    UUID member;
    UUID creditCustomer;
    UUID deliveryCustomer;
    UUID deliveryAddress;
    UUID zone;
    String businessDate;
    long catalogVersion;
    long promotionVersion;
    final List<String> sales = new ArrayList<>();

    DevSeed(String baseUrl, Path outDir) throws Exception {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.outDir = outDir;
        this.http = new LoadSmoke.Http(this.baseUrl, null);
    }

    public static void main(String[] args) {
        String baseUrl = "http://localhost:8080";
        Path outDir = Path.of(".dev-seed");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--base-url" -> baseUrl = value(args, ++i);
                case "--out-dir" -> outDir = Path.of(value(args, ++i));
                default -> {
                    System.err.println("dev-seed: unknown option " + args[i]
                            + " (usage: dev-seed.sh [--base-url http://localhost:8080])");
                    System.exit(2);
                }
            }
        }
        DevSeed seed = null;
        int exit;
        try {
            seed = new DevSeed(baseUrl, outDir);
            exit = seed.run();
        } catch (Exception e) {
            System.err.println("dev-seed: FAILED: " + e.getMessage());
            if (seed != null && seed.storeCreated) {
                System.err.println("dev-seed: store " + STORE + " is only partly seeded and confirmed facts cannot be "
                        + "deleted (spec §8). Reset the local dev database and run again:\n"
                        + "  docker compose down -v && ./run-local.sh   (then the admin's first login)");
            }
            exit = 1;
        }
        System.exit(exit);
    }

    static String value(String[] args, int i) {
        if (i >= args.length) {
            System.err.println("dev-seed: " + args[i - 1] + " needs a value");
            System.exit(2);
        }
        return args[i];
    }

    int run() throws Exception {
        admin = tab("admin");
        String username = System.getenv().getOrDefault("SEED_ADMIN_USERNAME", "admin");
        Object session = login(admin, username, adminPassword(username));
        if (session == null) {
            System.err.println("dev-seed: the server refused " + username + "'s password.");
            return 1;
        }
        if (Boolean.TRUE.equals(LoadSmoke.Json.get(session, "mustChangePassword"))) {
            System.err.println("dev-seed: " + username + " still has the initial password. Do the first login first "
                    + "(Swagger: POST /api/v1/auth/login, then POST /api/v1/auth/password), then run dev-seed again.");
            return 1;
        }
        UUID existing = findStore();
        if (existing != null) {
            store = existing;
            System.out.println("already seeded: store " + STORE + " exists (" + store + "); nothing was changed.");
            summary();
            return 0;
        }
        preflight();
        System.out.println("seeding store " + STORE + " on " + baseUrl + " ...");
        seedStore();
        seedPeople();
        publishCatalog();
        seedInventory();
        seedPromotionsAndLoyalty();
        seedCustomers();
        sell();
        System.out.println("seeded.");
        summary();
        return 0;
    }

    /** From SEED_ADMIN_PASSWORD_FILE, else a no-echo prompt; never from argv, never printed. */
    static String adminPassword(String username) throws Exception {
        String file = System.getenv("SEED_ADMIN_PASSWORD_FILE");
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file)).strip();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("no terminal to prompt on: set SEED_ADMIN_PASSWORD_FILE to a file holding "
                    + "the administrator's password");
        }
        char[] password = console.readPassword("Password of %s: ", username);
        if (password == null || password.length == 0) {
            throw new IllegalStateException("no password given");
        }
        return new String(password);
    }

    // ---- 1. store, terminals, tables, printers, series, register ----

    private void seedStore() throws Exception {
        store = id(post(admin, BO + "/stores", obj("code", STORE, "name", "Restaurante Dev", "timezone", ZONE,
                "businessDayCutoff", CUTOFF)));
        storeCreated = true;
        UUID area = id(post(admin, BO + "/areas", obj("storeId", store, "code", "SALA", "name", "Sala")));
        for (int t = 1; t <= 8; t++) {
            tables.put("M" + t, id(post(admin, BO + "/tables", obj("storeId", store, "areaId", area, "code", "M" + t,
                    "seats", t <= 4 ? 2 : 4))));
        }
        UUID kitchen = heldPrinter("COZ", "Cozinha");
        UUID bar = heldPrinter("BAR", "Bar");
        UUID counter = heldPrinter("BALCAO", "Balcão");
        put(admin, BO + "/production-routes", obj("storeId", store, "routes", List.of(
                obj("destinationCode", "COZINHA", "printerId", kitchen),
                obj("destinationCode", "BAR", "printerId", bar))));
        List<Object> assignments = new ArrayList<>();
        for (String code : List.of("POS-1", "POS-2")) {
            UUID terminal = id(post(admin, BO + "/terminals", obj("storeId", store, "code", code, "name", code)));
            terminals.put(code, terminal);
            assignments.add(obj("terminalId", terminal, "printerId", counter));
            String enrollment = LoadSmoke.Json.str(post(admin, BO + "/terminals/" + terminal + "/enrollment-code",
                    null), "code");
            LoadSmoke.Tab device = tab("enroll " + code);
            tokens.put(code, LoadSmoke.Json.str(post(device, "/api/v1/auth/terminals/enroll",
                    obj("code", enrollment)), "deviceToken"));
        }
        writeTokens();
        put(admin, BO + "/terminal-printers", obj("storeId", store, "assignments", assignments));
        post(admin, BO + "/fiscal/series", obj("storeId", store, "code", "FR", "firstNumber", 1));
        register = id(post(admin, BO + "/cash/registers", obj("storeId", store, "code", "CX1", "name", "Caixa 1")));
    }

    /**
     * A printer on HOLD at an unroutable address, as LoadSmoke does: its jobs stay QUEUED and nothing is sent. Point
     * it at a real printer (PUT /backoffice/printers/{id}) and set it to NORMAL to print.
     */
    private UUID heldPrinter(String code, String name) throws Exception {
        Object printer = post(admin, BO + "/printers", obj("storeId", store, "code", code, "name", name,
                "host", "127.0.0.1", "port", 9, "codePage", "CP860"));
        put(admin, BO + "/printers/" + id(printer) + "/mode", obj("mode", "HOLD", "version",
                LoadSmoke.Json.num(printer, "version")));
        return id(printer);
    }

    private void writeTokens() throws Exception {
        Files.createDirectories(outDir);
        Path file = outDir.resolve("terminals.txt");
        StringBuilder text = new StringBuilder("# restaurantPRO dev seed, store " + STORE + " (" + store + ").\n"
                + "# POS login: POST /api/v1/auth/pos-login with header X-Terminal-Token: <token below>.\n");
        tokens.forEach((code, token) -> text.append(code).append('=').append(token).append('\n'));
        Files.writeString(file, text);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: the file stays with the folder's permissions
        }
    }

    // ---- 2. employees (PINs) and BackOffice accounts ----

    private void seedPeople() throws Exception {
        for (Person person : PEOPLE) {
            employees.put(person.code(), id(post(admin, BO + "/employees", obj("code", person.code(), "name",
                    person.name(), "pin", person.pin(), "storeId", store, "roleId", person.role()))));
        }
        for (Account account : ACCOUNTS) {
            // a throwaway initial password, then the first-login change to the fixed one (D30), so it works at once
            String initial = "initial-" + UUID.randomUUID();
            post(admin, BO + "/users", obj("username", account.username(), "initialPassword", initial,
                    "employeeId", account.employee() == null ? null : employees.get(account.employee().code()),
                    "roleIds", List.of(account.role())));
            LoadSmoke.Tab tab = tab(account.username());
            if (login(tab, account.username(), initial) == null) {
                throw new IllegalStateException("the new account " + account.username() + " cannot log in");
            }
            post(tab, "/api/v1/auth/password", obj("currentPassword", initial, "newPassword", account.password()));
        }
    }

    // ---- 3. catalog: families, items, menu, fee; published ----

    private void publishCatalog() throws Exception {
        for (String[] family : FAMILIES) {
            families.put(family[0], id(post(admin, CATALOG + "/families", obj("code", family[0], "name", family[1]))));
        }
        UUID unit = id(post(admin, CATALOG + "/units", obj("code", "UN", "name", "Unidade")));
        UUID vat = id(post(admin, CATALOG + "/tax-categories", obj("code", "IVA16", "name", "IVA 16 %")));
        post(admin, CATALOG + "/tax-categories/" + vat + "/rates", obj("ratePercent", new BigDecimal("16"),
                "effectiveFrom", "2000-01-01T00:00:00Z"));
        UUID priceList = id(post(admin, CATALOG + "/price-lists", obj("storeId", store, "name", "Sala")));
        for (Dish dish : DISHES) {
            item(priceList, unit, vat, dish.code(), dish.name(), dish.family(), dish.destination(), "STANDARD",
                    dish.price());
        }
        item(priceList, unit, vat, MENU, "Menu do dia (prato + bebida)", "PRATOS", null, "MENU", "520.00");
        item(priceList, unit, vat, FEE, "Taxa de entrega", "TAXAS", null, "FEE", "100.00");
        menuGroup("PRATO", "Prato", 1, List.of("FRANGO", "CARIL", "OMELETE"));
        menuGroup("BEBIDA", "Bebida", 2, List.of("AGUA", "REFRIGERANTE", "SUMO"));
        priceOtherItems(priceList);
        UUID release = id(post(admin, CATALOG + "/releases", obj("storeId", store)));
        post(admin, CATALOG + "/releases/" + release + "/validate", null);
        post(admin, CATALOG + "/releases/" + release + "/publish", obj("note", "Dev seed"));
    }

    private void item(UUID priceList, UUID unit, UUID vat, String code, String name, String family,
                      String destination, String kind, String price) throws Exception {
        UUID item = id(post(admin, CATALOG + "/items", obj("code", code, "name", name, "familyId", families.get(family),
                "unitId", unit, "taxCategoryId", vat, "productionDestination", destination, "soldByWeight", false,
                "kind", kind)));
        post(admin, CATALOG + "/price-lists/" + priceList + "/prices", obj("itemId", item, "amount",
                new BigDecimal(price)));
        items.put(code, item);
    }

    private void menuGroup(String code, String name, int position, List<String> options) throws Exception {
        post(admin, CATALOG + "/items/" + items.get(MENU) + "/menu-groups", obj("code", code, "name", name,
                "minChoices", 1, "maxChoices", 1, "position", position, "options", options.stream()
                        .map(o -> obj("itemId", items.get(o), "quantity", BigDecimal.ONE)).toList()));
    }

    /** A release must price every active company item: items made before the seed (not ours) get 10.00 here. */
    private void priceOtherItems(UUID priceList) throws Exception {
        Set<String> ours = new HashSet<>();
        items.values().forEach(i -> ours.add(i.toString()));
        for (Object item : all(admin, CATALOG + "/items")) {
            if (!ours.contains(LoadSmoke.Json.str(item, "id"))) {
                post(admin, CATALOG + "/price-lists/" + priceList + "/prices", obj("itemId", LoadSmoke.id(item),
                        "amount", new BigDecimal("10.00")));
            }
        }
    }

    // ---- 5. inventory: warehouses, routes, stock items, recipes, opening counts, purchasing ----

    private void seedInventory() throws Exception {
        LoadSmoke.Tab stockTab = login(STOCK);
        LoadSmoke.Tab managerTab = login(MANAGER);
        UUID kitchen = id(post(stockTab, INV + "/warehouses", obj("storeId", store, "code", "COZ", "name",
                "Cozinha")));
        UUID bar = id(post(stockTab, INV + "/warehouses", obj("storeId", store, "code", "BAR", "name", "Bar")));
        post(stockTab, INV + "/consumption-routes", obj("storeId", store, "warehouseId", kitchen));
        post(stockTab, INV + "/consumption-routes", obj("storeId", store, "destinationCode", "BAR",
                "warehouseId", bar));
        for (String[] s : STOCK_ITEMS) {
            stock.put(s[0], id(post(stockTab, INV + "/stock-items", obj("code", s[0], "name", s[1], "baseUnit", s[2],
                    "kind", "PURCHASED"))));
        }
        for (Map.Entry<String, List<String[]>> r : RECIPES.entrySet()) {
            List<Object> ingredients = new ArrayList<>();
            for (String[] i : r.getValue()) {
                ingredients.add(ingredient(i[0], i[1], i[2], "INGREDIENT", null));
            }
            if (DISHES.stream().anyMatch(d -> d.code().equals(r.getKey()) && d.family().equals("PRATOS"))) {
                ingredients.add(ingredient("CAIXA_TAKEAWAY", "1", "un", "PACKAGING", List.of("TAKEAWAY", "DELIVERY")));
            }
            UUID recipe = id(post(stockTab, INV + "/recipes", obj("itemId", items.get(r.getKey()), "name",
                    "Ficha " + r.getKey())));
            post(stockTab, INV + "/recipes/" + recipe + "/versions", obj("yieldQuantity", BigDecimal.ONE,
                    "yieldUnit", "un", "ingredients", ingredients));
            post(stockTab, INV + "/recipes/" + recipe + "/versions/1/activate", obj());
        }
        consumptionPolicy();
        // the store goes live with an opening count per warehouse: counted by stock, approved by the manager
        opening(stockTab, managerTab, kitchen, KITCHEN_OPENING);
        opening(stockTab, managerTab, bar, BAR_OPENING);
        purchase(stockTab, kitchen);
    }

    private Map<String, Object> ingredient(String stockItem, String quantity, String unit, String kind,
                                           List<String> modes) {
        return obj("stockItemId", stock.get(stockItem), "quantity", new BigDecimal(quantity), "unit", unit,
                "kind", kind, "modes", modes);
    }

    /**
     * The delivery fee is not stock: a new version of the company-wide policy = the active version's rules (the
     * migration seeds "everything tracked") plus "family TAXAS not tracked".
     */
    private void consumptionPolicy() throws Exception {
        List<Object> rules = new ArrayList<>();
        for (Object version : (List<?>) get(admin, INV + "/consumption-policies")) {
            if ("ACTIVE".equals(LoadSmoke.Json.get(version, "status"))) {
                for (Object rule : LoadSmoke.Json.list(version, "rules")) {
                    rules.add(obj("familyId", LoadSmoke.Json.get(rule, "familyId"), "trigger",
                            LoadSmoke.Json.get(rule, "trigger"), "tracked", LoadSmoke.Json.get(rule, "tracked")));
                }
            }
        }
        rules.add(obj("familyId", families.get("TAXAS"), "tracked", false));
        Object policy = post(admin, INV + "/consumption-policies", obj("rules", rules));
        post(admin, INV + "/consumption-policies/" + LoadSmoke.Json.num(policy, "versionNo").toPlainString()
                + "/activate", obj());
    }

    private void opening(LoadSmoke.Tab counter, LoadSmoke.Tab approver, UUID warehouse, List<String[]> lines)
            throws Exception {
        UUID count = id(post(counter, INV + "/counts", obj("warehouseId", warehouse, "kind", "OPENING", "scope",
                "ALL")));
        post(counter, INV + "/counts/" + count + "/start", obj());
        for (String[] line : lines) {
            put(counter, INV + "/counts/" + count + "/lines/" + stock.get(line[0]), obj("countedQuantity",
                    new BigDecimal(line[1]), "unitCost", new BigDecimal(line[2])));
        }
        post(counter, INV + "/counts/" + count + "/submit", obj());
        post(approver, INV + "/counts/" + count + "/approve", obj());
    }

    /** Supplier, a submitted purchase order of chicken and potatoes, and one receipt of all of it, confirmed. */
    private void purchase(LoadSmoke.Tab tab, UUID kitchen) throws Exception {
        UUID supplier = id(post(tab, INV + "/suppliers", obj("code", "DISTMAP", "name", "Distribuidora Maputo Lda",
                "phone", "841234567")));
        Object order = post(tab, INV + "/purchase-orders", obj("warehouseId", kitchen, "supplierId", supplier,
                "number", "PO-DEV-1", "lines", List.of(
                        obj("stockItemId", stock.get("FRANGO"), "quantity", new BigDecimal("10"), "unitCost",
                                new BigDecimal("340.00")),
                        obj("stockItemId", stock.get("BATATA"), "quantity", new BigDecimal("20"), "unitCost",
                                new BigDecimal("55.00")))));
        UUID po = id(order);
        post(tab, INV + "/purchase-orders/" + po + "/submit", obj());
        List<Object> lines = new ArrayList<>();
        for (Object line : LoadSmoke.Json.list(order, "lines")) {
            lines.add(obj("purchaseOrderLineId", LoadSmoke.id(line), "stockItemId",
                    LoadSmoke.Json.str(line, "stockItemId"), "packQuantity", LoadSmoke.Json.num(line, "quantity"),
                    "unitCost", LoadSmoke.Json.num(line, "unitCost")));
        }
        UUID receipt = id(post(tab, INV + "/receipts", obj("warehouseId", kitchen, "supplierId", supplier,
                "purchaseOrderId", po, "supplierDocumentRef", "FT-DEV-001", "lines", lines)));
        post(tab, INV + "/receipts/" + receipt + "/confirm", obj());
    }

    // ---- 3b. promotions, discount limits, loyalty, card payments ----

    private void seedPromotionsAndLoyalty() throws Exception {
        String promotions = BO + "/promotions";
        Object hh = post(admin, promotions, obj("code", "HAPPY_HOUR", "name", "Happy hour bebidas", "kind",
                "PERCENT_OFF", "percent", new BigDecimal("20"), "priority", 1));
        UUID id = id(hh);
        Object scoped = put(admin, promotions + "/" + id + "/scope", obj("itemIds", List.of(), "variantIds",
                List.of(), "familyIds", List.of(families.get("BEBIDAS")), "version", LoadSmoke.Json.num(hh, "version")));
        Object windowed = put(admin, promotions + "/" + id + "/windows", obj("windows", List.of(obj("daysOfWeek",
                List.of(1, 2, 3, 4, 5, 6, 7), "start", "17:00", "end", "19:00")), "version",
                LoadSmoke.Json.num(scoped, "version")));
        put(admin, promotions + "/" + id + "/stores", obj("storeIds", List.of(store), "version",
                LoadSmoke.Json.num(windowed, "version")));
        put(admin, promotions + "/discount-limits/" + store, obj("freeLimitPercent", new BigDecimal("10"),
                "maxPercent", new BigDecimal("50")));
        UUID release = id(post(admin, promotions + "/releases", obj("storeId", store)));
        post(admin, promotions + "/releases/" + release + "/validate", obj());
        post(admin, promotions + "/releases/" + release + "/publish", obj());

        if (((List<?>) get(admin, BO + "/loyalty/program-versions")).isEmpty()) {
            String programs = BO + "/loyalty/program-versions";
            UUID program = id(post(admin, programs, obj("spendUnit", new BigDecimal("10.00"), "pointsPerUnit", 1,
                    "itemIds", List.of(), "familyIds", List.of())));
            post(admin, programs + "/" + program + "/rewards", obj("code", "CAFE_GRATIS", "name", "Café grátis",
                    "pointsCost", 50, "effect", "FREE_UNIT", "amount", null, "itemId", items.get("EXPRESSO"),
                    "familyId", null));
            post(admin, programs + "/" + program + "/publish", obj("version",
                    LoadSmoke.Json.num(get(admin, programs + "/" + program), "version")));
        } else {
            System.out.println("  note: a loyalty program already exists; kept as is");
        }

        boolean hasCard = ((List<?>) get(admin, BO + "/payment-methods")).stream()
                .anyMatch(m -> "CARTAO".equals(LoadSmoke.Json.get(m, "code")));
        if (!hasCard) {
            post(admin, BO + "/payment-methods", obj("code", "CARTAO", "name", "Cartão (TPA)", "kind",
                    "EXTERNAL_TERMINAL"));
        }
    }

    // ---- 4. customers and the delivery zone ----

    private void seedCustomers() throws Exception {
        member = id(post(admin, BO + "/customers", obj("name", "Marta Sitoe", "phone", "841112222", "email",
                "marta.sitoe@example.com")));
        creditCustomer = id(post(admin, BO + "/customers", obj("name", "Construtora Limpopo Lda", "phone",
                "842223333", "taxId", "400123456")));
        post(admin, BO + "/customers/" + creditCustomer + "/credit-limit-changes", obj("newLimit",
                new BigDecimal("20000.00"), "reason", "Cliente empresa (dev seed)"));
        deliveryCustomer = id(post(admin, BO + "/customers", obj("name", "Rui Tembe", "phone", "843334444")));
        deliveryAddress = id(post(admin, BO + "/customers/" + deliveryCustomer + "/addresses", obj("kind", "DELIVERY",
                "line1", "Av. Julius Nyerere 100", "city", "Maputo", "notes", "Portão azul")));
        zone = id(post(admin, BO + "/delivery-zones", obj("storeId", store, "code", "CENTRO", "name", "Centro",
                "feeItemId", items.get(FEE))));
    }

    // ---- 6. today's sales through the POS ----

    private void sell() throws Exception {
        LoadSmoke.Tab cashier = posLogin("POS-1", CAIXA);
        LoadSmoke.Tab waiter = posLogin("POS-2", EMPREGADO);
        Object session = post(cashier, POS + "/cash/sessions", obj("registerId", register, "openingFloat",
                new BigDecimal("2000.00")));
        businessDate = LoadSmoke.Json.str(session, "businessDate");
        catalogVersion = LoadSmoke.Json.num(get(cashier, POS + "/catalog?storeId=" + store), "version").longValue();
        promotionVersion = LoadSmoke.Json.num(get(cashier, POS + "/promotions/current"), "version").longValue();
        post(cashier, POS + "/customers/" + member + "/loyalty", null);
        post(admin, BO + "/loyalty/accounts/" + member + "/adjustments", obj("points", 50, "reason", "Boas-vindas",
                "storeId", store));

        UUID m1 = table(waiter, "M1", 2, "FRANGO 1", "CERVEJA 2", "EXPRESSO 2");
        payAndClose(cashier, m1, "cash", "M1: chicken, beers, coffees");

        UUID m2 = table(waiter, "M2", 2, "BIFE 1", "AGUA 1", "PUDIM 1");
        menu(waiter, m2, "FRANGO", "REFRIGERANTE");
        confirmDrafts(waiter, m2);
        payAndClose(cashier, m2, "card", "M2: menu of the day and a steak");

        UUID m3 = table(waiter, "M3", 4, "PEIXE 2", "CARIL 2", "SUMO 2", "CERVEJA 2");
        round(waiter, m3, "MOUSSE 2", "GALAO 2");
        List<?> parts = (List<?>) post(cashier, POS + "/orders/" + m3 + "/partitions/equal", obj("orderVersion",
                settlementVersion(cashier, m3), "parts", 2));
        cash(cashier, m3, LoadSmoke.id(parts.get(0)), LoadSmoke.Json.money(parts.get(0), "due"));
        card(cashier, m3, LoadSmoke.id(parts.get(1)), LoadSmoke.Json.money(parts.get(1), "due"));
        close(cashier, m3);
        sales.add("M3: two rounds, split in 2 equal parts (cash + card)");

        UUID m4 = table(waiter, "M4", 3, "BIFE 2", "CERVEJA 3", "SALADA_FRUTA 1");
        Object view = get(waiter, POS + "/orders/" + m4);
        post(waiter, POS + "/orders/" + m4 + "/discounts", obj("orderVersion", LoadSmoke.Json.num(view, "version"),
                "kind", "DISCOUNT", "percent", new BigDecimal("20"), "reason", "Cliente habitual", "lines",
                List.of(obj("lineId", lineOf(view, "BIFE")))), Map.of("X-Approver-Code", GERENTE.code(),
                "X-Approver-Pin", GERENTE.pin()));
        payAndClose(cashier, m4, "cash", "M4: 20 % off the steaks, approved by the Gerente");

        UUID m5 = id(post(waiter, POS + "/tables/" + tables.get("M5") + "/open", obj("guests", 2)));
        post(waiter, POS + "/orders/" + m5 + "/customer", obj("orderVersion", version(waiter, m5), "customerId",
                member));
        round(waiter, m5, "CARIL 1", "OMELETE 1", "BATIDO 2", "CAPPUCCINO 2");
        payAndClose(cashier, m5, "card", "M5: loyalty member Marta Sitoe earns points");

        delivery(cashier);

        UUID m6 = table(waiter, "M6", 1, "DUPLO 1", "ARROZ_DOCE 1");
        payAndClose(cashier, m6, "cash", "M6: coffee and dessert");

        UUID m7 = id(post(waiter, POS + "/tables/" + tables.get("M7") + "/open", obj("guests", 2)));
        menu(waiter, m7, "CARIL", "SUMO");
        menu(waiter, m7, "OMELETE", "AGUA");
        confirmDrafts(waiter, m7);
        payAndClose(cashier, m7, "card", "M7: two menus of the day");

        UUID m1b = table(waiter, "M1", 2, "PEIXE 1", "CERVEJA 1", "EXPRESSO 1");
        payAndClose(cashier, m1b, "cash", "M1 again: fish, beer, coffee");

        table(waiter, "M8", 4, "FRANGO 2", "BIFE 1", "CERVEJA 4");
        sales.add("M8: left OPEN with a confirmed round (live state)");
    }

    /** Opens {@code code} for {@code guests} and confirms one round of "ITEM quantity" lines. */
    private UUID table(LoadSmoke.Tab waiter, String code, int guests, String... lines) throws Exception {
        UUID order = id(post(waiter, POS + "/tables/" + tables.get(code) + "/open", obj("guests", guests)));
        round(waiter, order, lines);
        return order;
    }

    private void round(LoadSmoke.Tab tab, UUID order, String... lines) throws Exception {
        for (String line : lines) {
            String[] parts = line.split(" ");
            post(tab, POS + "/orders/" + order + "/lines", obj("itemId", items.get(parts[0]), "quantity",
                    new BigDecimal(parts[1])));
        }
        confirmDrafts(tab, order);
    }

    private void menu(LoadSmoke.Tab tab, UUID order, String dish, String drink) throws Exception {
        post(tab, POS + "/orders/" + order + "/lines", obj("itemId", items.get(MENU), "quantity", BigDecimal.ONE,
                "components", List.of(obj("groupCode", "PRATO", "itemId", items.get(dish)),
                        obj("groupCode", "BEBIDA", "itemId", items.get(drink)))));
    }

    /** Confirms every draft with the catalog and promotion versions and the preview gross the POS showed (D104). */
    private void confirmDrafts(LoadSmoke.Tab tab, UUID order) throws Exception {
        Object view = get(tab, POS + "/orders/" + order);
        List<Object> drafts = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        for (Object line : LoadSmoke.Json.list(view, "lines")) {
            if ("DRAFT".equals(LoadSmoke.Json.str(line, "status"))) {
                drafts.add(LoadSmoke.id(line));
                gross = gross.add(LoadSmoke.Json.money(line, "gross"));
            }
        }
        post(tab, POS + "/orders/" + order + "/batches", obj("orderVersion", LoadSmoke.Json.num(view, "version"),
                "catalogVersion", catalogVersion, "lineIds", drafts, "promotionVersion", promotionVersion,
                "expectedGross", gross));
    }

    /** A delivery to Rui Tembe: zone fee, dispatched with the Estafeta, delivered, cash on return, closed. */
    private void delivery(LoadSmoke.Tab cashier) throws Exception {
        UUID order = id(post(cashier, POS + "/orders", obj("mode", "DELIVERY", "delivery", obj("customerId",
                deliveryCustomer, "addressId", deliveryAddress, "zoneId", zone))));
        round(cashier, order, "FRANGO 2", "REFRIGERANTE 2");
        Object delivery = ((List<?>) get(cashier, POS + "/deliveries")).stream()
                .filter(d -> order.toString().equals(LoadSmoke.Json.get(d, "orderId"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("delivery " + order + " is not on the board"));
        Object dispatched = post(cashier, POS + "/deliveries/" + order + "/dispatch", obj("version",
                LoadSmoke.Json.num(delivery, "version"), "courierEmployeeId", employees.get(ESTAFETA.code())));
        post(cashier, POS + "/deliveries/" + order + "/delivered", obj("version",
                LoadSmoke.Json.num(dispatched, "version")));
        payAndClose(cashier, order, "cash", "Delivery to Rui Tembe (zone CENTRO + fee), Estafeta, cash on return");
    }

    private void payAndClose(LoadSmoke.Tab cashier, UUID order, String method, String story) throws Exception {
        BigDecimal due = LoadSmoke.Json.money(get(cashier, POS + "/orders/" + order + "/settlement"), "due");
        if (method.equals("card")) {
            card(cashier, order, null, due);
        } else {
            cash(cashier, order, null, due);
        }
        close(cashier, order);
        sales.add(story + " (" + due.toPlainString() + " MZN, " + method + ")");
    }

    /** Cash, tendered rounded up to the next 100 (the change is given in cash). */
    private void cash(LoadSmoke.Tab cashier, UUID order, UUID partition, BigDecimal amount) throws Exception {
        BigDecimal tendered = amount.divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
                .multiply(BigDecimal.valueOf(100));
        post(cashier, POS + "/payment-attempts", obj("orderId", order, "partitionId", partition, "methodCode", "CASH",
                "amount", amount, "tendered", tendered));
    }

    /** A card on the external terminal (TPA): the attempt is PENDING until the cashier confirms the approval. */
    private void card(LoadSmoke.Tab cashier, UUID order, UUID partition, BigDecimal amount) throws Exception {
        Object attempt = post(cashier, POS + "/payment-attempts", obj("orderId", order, "partitionId", partition,
                "methodCode", "CARTAO", "amount", amount));
        post(cashier, POS + "/payment-attempts/" + LoadSmoke.id(attempt) + "/confirm", obj("authorizationCode",
                "DEV" + (100000 + sales.size())));
    }

    private void close(LoadSmoke.Tab cashier, UUID order) throws Exception {
        Object closed = post(cashier, POS + "/orders/" + order + "/close", obj("orderVersion",
                settlementVersion(cashier, order)));
        if (!"CLOSED".equals(LoadSmoke.Json.get(closed, "lifecycle"))) {
            throw new IllegalStateException("order " + order + " did not close: " + LoadSmoke.Json.write(closed));
        }
    }

    private long settlementVersion(LoadSmoke.Tab tab, UUID order) throws Exception {
        return LoadSmoke.Json.num(get(tab, POS + "/orders/" + order + "/settlement"), "orderVersion").longValue();
    }

    private long version(LoadSmoke.Tab tab, UUID order) throws Exception {
        return LoadSmoke.Json.num(get(tab, POS + "/orders/" + order), "version").longValue();
    }

    private UUID lineOf(Object order, String item) {
        for (Object line : LoadSmoke.Json.list(order, "lines")) {
            if (items.get(item).toString().equals(LoadSmoke.Json.get(line, "itemId"))) {
                return LoadSmoke.id(line);
            }
        }
        throw new IllegalStateException("no " + item + " line on " + LoadSmoke.Json.write(order));
    }

    // ---- re-run detection and the conflict check ----

    private UUID findStore() throws Exception {
        for (Object s : all(admin, BO + "/stores")) {
            if (STORE.equals(LoadSmoke.Json.get(s, "code"))) {
                return LoadSmoke.id(s);
            }
        }
        return null;
    }

    /**
     * The seed's company-wide codes must be free, checked before anything is written: a clash half-way would leave a
     * partial store DEV, and confirmed facts cannot be deleted.
     */
    private void preflight() throws Exception {
        Map<String, List<String>> wanted = new LinkedHashMap<>();
        wanted.put(BO + "/users|username", ACCOUNTS.stream().map(Account::username).toList());
        wanted.put(BO + "/employees|code", PEOPLE.stream().map(Person::code).toList());
        List<String> itemCodes = new ArrayList<>(DISHES.stream().map(Dish::code).toList());
        itemCodes.addAll(List.of(MENU, FEE));
        wanted.put(CATALOG + "/items|code", itemCodes);
        wanted.put(CATALOG + "/families|code", FAMILIES.stream().map(f -> f[0]).toList());
        wanted.put(CATALOG + "/units|code", List.of("UN"));
        wanted.put(CATALOG + "/tax-categories|code", List.of("IVA16"));
        wanted.put(INV + "/stock-items|code", STOCK_ITEMS.stream().map(s -> s[0]).toList());
        wanted.put(INV + "/suppliers|code", List.of("DISTMAP"));
        wanted.put(BO + "/promotions|code", List.of("HAPPY_HOUR"));
        List<String> clashes = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : wanted.entrySet()) {
            String[] pathAndField = e.getKey().split("\\|");
            Set<String> taken = new HashSet<>();
            for (Object row : all(admin, pathAndField[0])) {
                Object value = LoadSmoke.Json.get(row, pathAndField[1]);
                if (value != null) {
                    taken.add(value.toString().toUpperCase(Locale.ROOT));
                }
            }
            e.getValue().stream().filter(c -> taken.contains(c.toUpperCase(Locale.ROOT)))
                    .forEach(c -> clashes.add(pathAndField[0].replace("/api/v1", "") + " " + c));
        }
        if (!clashes.isEmpty()) {
            throw new IllegalStateException("this installation already has records with the seed's codes, so nothing "
                    + "was created: " + clashes + ". The seed is meant for a fresh dev database (docker compose "
                    + "down -v && ./run-local.sh).");
        }
    }

    // ---- the summary ----

    private void summary() {
        Path tokenFile = outDir.toAbsolutePath().normalize().resolve("terminals.txt");
        StringBuilder out = new StringBuilder();
        out.append("\n=== restaurantPRO dev seed (DEV ONLY: fixed credentials) ===\n");
        out.append("Store        ").append(STORE).append(" \"Restaurante Dev\", ").append(ZONE).append(", id ")
                .append(store).append('\n');
        out.append("Swagger UI   ").append(baseUrl).append("/swagger-ui/index.html\n\n");
        out.append("BackOffice logins (POST /api/v1/auth/login):\n");
        for (Account a : ACCOUNTS) {
            out.append(String.format("  %-9s %-15s %s%n", a.username(), a.password(), a.job()));
        }
        out.append("\nPOS employees (POST /api/v1/auth/pos-login, store ").append(STORE).append("):\n");
        for (Person p : PEOPLE) {
            out.append(String.format("  %-10s PIN %s  %s%n", p.code(), p.pin(), p.job()));
        }
        out.append("\nTerminals    POS-1, POS-2; device tokens in ").append(tokenFile)
                .append(Files.exists(tokenFile) ? "" : " (not on this machine: it was written where the seed ran)")
                .append('\n');
        if (!sales.isEmpty()) {
            out.append("\nSales of business day ").append(businessDate).append(":\n");
            sales.forEach(s -> out.append("  ").append(s).append('\n'));
        }
        out.append("""

                POS login in Swagger (one session cookie per browser: log out of the BackOffice first):
                  1. POST /api/v1/auth/logout (if logged in), then any GET such as GET /api/v1/auth/session
                     so the XSRF-TOKEN cookie is set.
                  2. POST /api/v1/auth/pos-login with header X-Terminal-Token = the POS-1 token from the file above
                     and body {"employeeCode": "CAIXA", "pin": "2222"}.
                  3. Call /api/v1/pos/... (the Caixa has an open cash session on POS-1).
                """);
        System.out.print(out);
    }

    // ---- HTTP: LoadSmoke's client, a tab per person ----

    private LoadSmoke.Tab tab(String name) throws Exception {
        LoadSmoke.Tab tab = new LoadSmoke.Tab(name, http);
        tab.send("GET", "/api/v1/auth/session", null, Map.of()); // 401, but sets the XSRF-TOKEN cookie
        return tab;
    }

    /** The session, or null when the password is refused. */
    private static Object login(LoadSmoke.Tab tab, String username, String password) throws Exception {
        LoadSmoke.Response response = tab.send("POST", "/api/v1/auth/login", obj("username", username, "password",
                password), Map.of());
        if (response.status() == 401) {
            return null;
        }
        return check(tab, "POST", "/api/v1/auth/login", response);
    }

    private LoadSmoke.Tab login(Account account) throws Exception {
        LoadSmoke.Tab tab = tab(account.username());
        if (login(tab, account.username(), account.password()) == null) {
            throw new IllegalStateException(account.username() + " cannot log in with its seeded password");
        }
        return tab;
    }

    private LoadSmoke.Tab posLogin(String terminal, Person person) throws Exception {
        LoadSmoke.Tab tab = tab(person.code() + "@" + terminal);
        post(tab, "/api/v1/auth/pos-login", obj("employeeCode", person.code(), "pin", person.pin()),
                Map.of("X-Terminal-Token", tokens.get(terminal)));
        return tab;
    }

    /** Every row of a list endpoint: a plain array, or a page walked 200 at a time. */
    private static List<Object> all(LoadSmoke.Tab tab, String path) throws Exception {
        List<Object> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            Object body = get(tab, path + (path.contains("?") ? "&" : "?") + "page=" + page + "&size=200");
            if (body instanceof List<?> list) {
                rows.addAll(list);
                return rows;
            }
            List<?> content = LoadSmoke.Json.list(body, "content");
            rows.addAll(content);
            if (content.size() < 200) {
                return rows;
            }
        }
    }

    static Object get(LoadSmoke.Tab tab, String path) throws Exception {
        return check(tab, "GET", path, tab.send("GET", path, null, Map.of()));
    }

    static Object post(LoadSmoke.Tab tab, String path, Object body) throws Exception {
        return post(tab, path, body, Map.of());
    }

    static Object post(LoadSmoke.Tab tab, String path, Object body, Map<String, String> headers) throws Exception {
        return check(tab, "POST", path, tab.send("POST", path, body, headers));
    }

    static Object put(LoadSmoke.Tab tab, String path, Object body) throws Exception {
        return check(tab, "PUT", path, tab.send("PUT", path, body, Map.of()));
    }

    private static Object check(LoadSmoke.Tab tab, String method, String path, LoadSmoke.Response response) {
        if (response.status() < 200 || response.status() > 299) {
            throw new IllegalStateException(tab.name + ": " + method + " " + path + " answered " + response.status()
                    + " " + LoadSmoke.clip(response.body()));
        }
        return response.json();
    }

    static Map<String, Object> obj(Object... keysAndValues) {
        return LoadSmoke.Json.obj(keysAndValues);
    }

    static UUID id(Object node) {
        return LoadSmoke.id(node);
    }
}
