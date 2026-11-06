# BUG-0001: the D97 payment breakdown prints "?" instead of the em dash on every printer table but WPC1252

- **Severity:** Medium. The sale document (the legal paper the customer takes) reads `Pagamento misto ? Numerário: …` on the Portuguese CP860 table and on CP437, CP850 and CP858. No money, number or snapshot is wrong; the printed text is.
- **Breaks:** r3-decisions §18 D97 rule 6: "The em dash is U+2014 with a space on each side, exactly as the user wrote it", and the user decision "Show the breakdown: 'Pagamento misto — Numerário: X; A crédito: Y'". Spec §4 (printing), §8.2 (the issued document).
- **Environment:** branch `s33-integrate` at `511e434` (feat/backend-stage3 `6da5d81` + S33 tests), macOS 15.3 x86_64, Java 25.0.4.1, PostgreSQL 18.6 in Docker. Internal fiscal adapter, production print worker, `TcpFakePrinter`.
- **Owner:** `developer` (fiscal adapter printout / production `EscPosRenderer`); the expected wording on a table without the dash needs `solution-architect` or the user (open question below).

## Steps to reproduce

1. Configure a counter printer with code page `CP860` (the Portuguese table, used by the R2 journey) and assign it to a terminal.
2. On that terminal, pay part of an order in cash and put the rest on a customer's account (`POST /api/v1/pos/orders/{id}/credit-charges`), so the document is issued with status MIXED.
3. Read the bytes the printer receives, decoded with CP860.

Or, without a server: `rtk mvn test -Dtest=PaymentBreakdownCodePageRepro -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition`.

## Expected

The breakdown line as D97 rule 6 fixes it: `Pagamento misto — Numerário: 100.00; A crédito: 50.00` (U+2014 surrounded by spaces), on every supported printer table, or, if a table cannot print U+2014, a deliberate, documented substitute (see the open question). Never a replacement character.

## Actual

`EscPosRenderer.text` encodes with `line.getBytes(codePage.charset())`, which replaces an unmappable character with `?` (its own Javadoc: "Characters the table lacks print as ?"). U+2014 exists only in windows-1252 among `CodePage`'s five tables:

```
IBM860 -> ? Numerário
IBM850 -> ? Numerário
IBM00858 -> ? Numerário
windows-1252 -> — Numerário
```

Repro run (4 parameterized cases, all red):

```
[ERROR]   PaymentBreakdownCodePageRepro.theBreakdownSeparatorSurvivesThePrintersTable:35
Expecting actual:
  "@tPagamento misto ? Numerário: 20.00; A crédito: 30.00
dVB "
not to contain:
  "misto ? Numer"
[ERROR] Tests run: 4, Failures: 4  (CP437, CP850, CP860, CP858)
```

The module tests (`InternalPrintoutPaymentsTest`, `DocumentPaymentBreakdownTest`) assert the printout lines before encoding, so they pass; only the bytes on the wire show the defect. `R3AcceptanceTest` uses WPC1252 counter printers so that it can assert D97's exact text end to end.

## Reproducing test

`backend/src/test/java/com/xavier/backend/production/adapter/PaymentBreakdownCodePageRepro.java` (`@Disabled("BUG-0001 …")`, named outside Surefire's include patterns so the gate never lists it as skipped).

## Open question (not decided by QA)

What should a CP860/CP850/CP437/CP858 printer show in place of U+2014: a hyphen (`Pagamento misto - Numerário`), another separator, or should the installation require WPC1252 for document printers? D97 says "exactly as the user wrote it", which a CP860 printer cannot do.
