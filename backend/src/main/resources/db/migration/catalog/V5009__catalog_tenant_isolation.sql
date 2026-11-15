-- R6 S74d: the working copy (items, families, units, taxes, menus, macros) becomes per tenant; the store tables keep their store policies and gain the tenant one (D174, D179, D180 as amended; spec "Per-table migration recipe").
-- No UPDATE runs: every new column takes a constant default (catalog-only, no rewrite), then the database default
-- shared.rls_tenant_id(), so an insert takes the bound tenant and an unbound one raises RS003. The bootstrap tenant
-- (P70 = none in production) is the fixed literal of every R6 migration and of TenantTestSupport.BOOTSTRAP.
-- Generated from the schema this module's earlier migrations built; every constraint keeps its name, so the
-- application's mapping of a violation by constraint name (UniqueConstraints) is unchanged.

-- (1)+(2) tenant_id on every table.
ALTER TABLE catalog.automatic_item
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.automatic_item
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.barcode
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.barcode
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.configuration_release
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.configuration_release
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.daily_dish
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.daily_dish
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.family
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.family
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.item
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.item
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.item_availability
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.item_availability
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.item_price
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.item_price
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.item_variant
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.item_variant
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.macro
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.macro
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.macro_entry
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.macro_entry
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.menu_group
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.menu_group
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.menu_option
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.menu_option
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.price_list
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.price_list
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.quick_page
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.quick_page
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.quick_page_entry
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.quick_page_entry
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.store_item_exclusion
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.store_item_exclusion
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.tax_category
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.tax_category
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.tax_rate
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.tax_rate
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();
ALTER TABLE catalog.unit
    ADD COLUMN tenant_id uuid NOT NULL DEFAULT '00000000-0000-7000-8000-000000000001';
ALTER TABLE catalog.unit
    ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id();

-- (3) UNIQUE (tenant_id, id) on every table with an id: the target of composite foreign keys.
ALTER TABLE catalog.automatic_item
    ADD CONSTRAINT automatic_item_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.barcode
    ADD CONSTRAINT barcode_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.configuration_release
    ADD CONSTRAINT configuration_release_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.family
    ADD CONSTRAINT family_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.item
    ADD CONSTRAINT item_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.item_availability
    ADD CONSTRAINT item_availability_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.item_price
    ADD CONSTRAINT item_price_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.item_variant
    ADD CONSTRAINT item_variant_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.macro
    ADD CONSTRAINT macro_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.menu_group
    ADD CONSTRAINT menu_group_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.price_list
    ADD CONSTRAINT price_list_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.quick_page
    ADD CONSTRAINT quick_page_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.tax_category
    ADD CONSTRAINT tax_category_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.tax_rate
    ADD CONSTRAINT tax_rate_tenant_id_uk UNIQUE (tenant_id, id);
ALTER TABLE catalog.unit
    ADD CONSTRAINT unit_tenant_id_uk UNIQUE (tenant_id, id);

-- (4) Business unique keys per tenant: create the (tenant_id, ...) one, then (after the foreign keys moved) drop
-- the old and keep its name.
ALTER TABLE catalog.barcode
    ADD CONSTRAINT barcode_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE catalog.configuration_release
    ADD CONSTRAINT configuration_release_store_version_uk_tenant UNIQUE (tenant_id, store_id, catalog_version);
ALTER TABLE catalog.family
    ADD CONSTRAINT family_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE catalog.item
    ADD CONSTRAINT item_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE catalog.item_price
    ADD CONSTRAINT item_price_list_item_variant_uk_tenant UNIQUE NULLS NOT DISTINCT (tenant_id, price_list_id, item_id, variant_id);
ALTER TABLE catalog.item_variant
    ADD CONSTRAINT item_variant_id_item_uk_tenant UNIQUE (tenant_id, id, item_id);
ALTER TABLE catalog.item_variant
    ADD CONSTRAINT item_variant_item_code_uk_tenant UNIQUE (tenant_id, item_id, code);
ALTER TABLE catalog.macro
    ADD CONSTRAINT macro_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE catalog.menu_group
    ADD CONSTRAINT menu_group_item_code_uk_tenant UNIQUE (tenant_id, item_id, code);
ALTER TABLE catalog.menu_option
    ADD CONSTRAINT menu_option_group_item_variant_uk_tenant UNIQUE NULLS NOT DISTINCT (tenant_id, group_id, item_id, variant_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE catalog.price_list
    ADD CONSTRAINT price_list_id_currency_uk_tenant UNIQUE (tenant_id, id, currency);
ALTER TABLE catalog.price_list
    ADD CONSTRAINT price_list_store_uk_tenant UNIQUE (tenant_id, store_id);
ALTER TABLE catalog.quick_page
    ADD CONSTRAINT quick_page_store_code_uk_tenant UNIQUE NULLS NOT DISTINCT (tenant_id, store_id, code);
ALTER TABLE catalog.tax_category
    ADD CONSTRAINT tax_category_code_uk_tenant UNIQUE (tenant_id, code);
ALTER TABLE catalog.tax_rate
    ADD CONSTRAINT tax_rate_category_effective_uk_tenant UNIQUE (tenant_id, tax_category_id, effective_from);
ALTER TABLE catalog.unit
    ADD CONSTRAINT unit_code_uk_tenant UNIQUE (tenant_id, code);
CREATE UNIQUE INDEX automatic_item_active_ux_tenant ON catalog.automatic_item USING btree (tenant_id, store_id, trigger, mode, item_id, variant_id) NULLS NOT DISTINCT WHERE active;
CREATE UNIQUE INDEX configuration_release_one_published_per_store_ux_tenant ON catalog.configuration_release USING btree (tenant_id, store_id) WHERE ((status)::text = 'PUBLISHED'::text);
CREATE UNIQUE INDEX item_availability_store_item_variant_ux_tenant ON catalog.item_availability USING btree (tenant_id, store_id, item_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- (5) In-module foreign keys become composite (foreign keys bypass RLS, D179): NOT VALID, VALIDATE, drop the
-- single-column one, keep its name.
ALTER TABLE catalog.automatic_item
    ADD CONSTRAINT automatic_item_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.automatic_item
    VALIDATE CONSTRAINT automatic_item_item_fk_tenant;
ALTER TABLE catalog.automatic_item
    ADD CONSTRAINT automatic_item_variant_of_item_fk_tenant FOREIGN KEY (tenant_id, variant_id, item_id)
        REFERENCES catalog.item_variant (tenant_id, id, item_id) NOT VALID;
ALTER TABLE catalog.automatic_item
    VALIDATE CONSTRAINT automatic_item_variant_of_item_fk_tenant;
ALTER TABLE catalog.barcode
    ADD CONSTRAINT barcode_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.barcode
    VALIDATE CONSTRAINT barcode_item_fk_tenant;
ALTER TABLE catalog.barcode
    ADD CONSTRAINT barcode_variant_of_item_fk_tenant FOREIGN KEY (tenant_id, variant_id, item_id)
        REFERENCES catalog.item_variant (tenant_id, id, item_id) NOT VALID;
ALTER TABLE catalog.barcode
    VALIDATE CONSTRAINT barcode_variant_of_item_fk_tenant;
ALTER TABLE catalog.daily_dish
    ADD CONSTRAINT daily_dish_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.daily_dish
    VALIDATE CONSTRAINT daily_dish_item_fk_tenant;
ALTER TABLE catalog.family
    ADD CONSTRAINT family_parent_fk_tenant FOREIGN KEY (tenant_id, parent_id)
        REFERENCES catalog.family (tenant_id, id) NOT VALID;
ALTER TABLE catalog.family
    VALIDATE CONSTRAINT family_parent_fk_tenant;
ALTER TABLE catalog.item
    ADD CONSTRAINT item_family_fk_tenant FOREIGN KEY (tenant_id, family_id)
        REFERENCES catalog.family (tenant_id, id) NOT VALID;
ALTER TABLE catalog.item
    VALIDATE CONSTRAINT item_family_fk_tenant;
ALTER TABLE catalog.item
    ADD CONSTRAINT item_tax_category_fk_tenant FOREIGN KEY (tenant_id, tax_category_id)
        REFERENCES catalog.tax_category (tenant_id, id) NOT VALID;
ALTER TABLE catalog.item
    VALIDATE CONSTRAINT item_tax_category_fk_tenant;
ALTER TABLE catalog.item
    ADD CONSTRAINT item_unit_fk_tenant FOREIGN KEY (tenant_id, unit_id)
        REFERENCES catalog.unit (tenant_id, id) NOT VALID;
ALTER TABLE catalog.item
    VALIDATE CONSTRAINT item_unit_fk_tenant;
ALTER TABLE catalog.item_availability
    ADD CONSTRAINT item_availability_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.item_availability
    VALIDATE CONSTRAINT item_availability_item_fk_tenant;
ALTER TABLE catalog.item_availability
    ADD CONSTRAINT item_availability_variant_of_item_fk_tenant FOREIGN KEY (tenant_id, variant_id, item_id)
        REFERENCES catalog.item_variant (tenant_id, id, item_id) NOT VALID;
ALTER TABLE catalog.item_availability
    VALIDATE CONSTRAINT item_availability_variant_of_item_fk_tenant;
ALTER TABLE catalog.item_price
    ADD CONSTRAINT item_price_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.item_price
    VALIDATE CONSTRAINT item_price_item_fk_tenant;
ALTER TABLE catalog.item_price
    ADD CONSTRAINT item_price_list_currency_fk_tenant FOREIGN KEY (tenant_id, price_list_id, currency)
        REFERENCES catalog.price_list (tenant_id, id, currency) NOT VALID;
ALTER TABLE catalog.item_price
    VALIDATE CONSTRAINT item_price_list_currency_fk_tenant;
ALTER TABLE catalog.item_price
    ADD CONSTRAINT item_price_list_fk_tenant FOREIGN KEY (tenant_id, price_list_id)
        REFERENCES catalog.price_list (tenant_id, id) NOT VALID;
ALTER TABLE catalog.item_price
    VALIDATE CONSTRAINT item_price_list_fk_tenant;
ALTER TABLE catalog.item_price
    ADD CONSTRAINT item_price_variant_of_item_fk_tenant FOREIGN KEY (tenant_id, variant_id, item_id)
        REFERENCES catalog.item_variant (tenant_id, id, item_id) NOT VALID;
ALTER TABLE catalog.item_price
    VALIDATE CONSTRAINT item_price_variant_of_item_fk_tenant;
ALTER TABLE catalog.item_variant
    ADD CONSTRAINT item_variant_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.item_variant
    VALIDATE CONSTRAINT item_variant_item_fk_tenant;
ALTER TABLE catalog.macro_entry
    ADD CONSTRAINT macro_entry_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.macro_entry
    VALIDATE CONSTRAINT macro_entry_item_fk_tenant;
ALTER TABLE catalog.macro_entry
    ADD CONSTRAINT macro_entry_macro_fk_tenant FOREIGN KEY (tenant_id, macro_id)
        REFERENCES catalog.macro (tenant_id, id) ON DELETE CASCADE NOT VALID;
ALTER TABLE catalog.macro_entry
    VALIDATE CONSTRAINT macro_entry_macro_fk_tenant;
ALTER TABLE catalog.macro_entry
    ADD CONSTRAINT macro_entry_variant_of_item_fk_tenant FOREIGN KEY (tenant_id, variant_id, item_id)
        REFERENCES catalog.item_variant (tenant_id, id, item_id) NOT VALID;
ALTER TABLE catalog.macro_entry
    VALIDATE CONSTRAINT macro_entry_variant_of_item_fk_tenant;
ALTER TABLE catalog.menu_group
    ADD CONSTRAINT menu_group_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.menu_group
    VALIDATE CONSTRAINT menu_group_item_fk_tenant;
ALTER TABLE catalog.menu_option
    ADD CONSTRAINT menu_option_group_fk_tenant FOREIGN KEY (tenant_id, group_id)
        REFERENCES catalog.menu_group (tenant_id, id) ON DELETE CASCADE NOT VALID;
ALTER TABLE catalog.menu_option
    VALIDATE CONSTRAINT menu_option_group_fk_tenant;
ALTER TABLE catalog.menu_option
    ADD CONSTRAINT menu_option_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.menu_option
    VALIDATE CONSTRAINT menu_option_item_fk_tenant;
ALTER TABLE catalog.menu_option
    ADD CONSTRAINT menu_option_variant_of_item_fk_tenant FOREIGN KEY (tenant_id, variant_id, item_id)
        REFERENCES catalog.item_variant (tenant_id, id, item_id) NOT VALID;
ALTER TABLE catalog.menu_option
    VALIDATE CONSTRAINT menu_option_variant_of_item_fk_tenant;
ALTER TABLE catalog.quick_page_entry
    ADD CONSTRAINT quick_page_entry_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.quick_page_entry
    VALIDATE CONSTRAINT quick_page_entry_item_fk_tenant;
ALTER TABLE catalog.quick_page_entry
    ADD CONSTRAINT quick_page_entry_macro_fk_tenant FOREIGN KEY (tenant_id, macro_id)
        REFERENCES catalog.macro (tenant_id, id) NOT VALID;
ALTER TABLE catalog.quick_page_entry
    VALIDATE CONSTRAINT quick_page_entry_macro_fk_tenant;
ALTER TABLE catalog.quick_page_entry
    ADD CONSTRAINT quick_page_entry_page_fk_tenant FOREIGN KEY (tenant_id, page_id)
        REFERENCES catalog.quick_page (tenant_id, id) ON DELETE CASCADE NOT VALID;
ALTER TABLE catalog.quick_page_entry
    VALIDATE CONSTRAINT quick_page_entry_page_fk_tenant;
ALTER TABLE catalog.quick_page_entry
    ADD CONSTRAINT quick_page_entry_variant_fk_tenant FOREIGN KEY (tenant_id, variant_id)
        REFERENCES catalog.item_variant (tenant_id, id) NOT VALID;
ALTER TABLE catalog.quick_page_entry
    VALIDATE CONSTRAINT quick_page_entry_variant_fk_tenant;
ALTER TABLE catalog.store_item_exclusion
    ADD CONSTRAINT store_item_exclusion_item_fk_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES catalog.item (tenant_id, id) NOT VALID;
ALTER TABLE catalog.store_item_exclusion
    VALIDATE CONSTRAINT store_item_exclusion_item_fk_tenant;
ALTER TABLE catalog.tax_rate
    ADD CONSTRAINT tax_rate_category_fk_tenant FOREIGN KEY (tenant_id, tax_category_id)
        REFERENCES catalog.tax_category (tenant_id, id) NOT VALID;
ALTER TABLE catalog.tax_rate
    VALIDATE CONSTRAINT tax_rate_category_fk_tenant;
ALTER TABLE catalog.automatic_item
    DROP CONSTRAINT automatic_item_item_fk;
ALTER TABLE catalog.automatic_item
    DROP CONSTRAINT automatic_item_variant_of_item_fk;
ALTER TABLE catalog.barcode
    DROP CONSTRAINT barcode_item_fk;
ALTER TABLE catalog.barcode
    DROP CONSTRAINT barcode_variant_of_item_fk;
ALTER TABLE catalog.daily_dish
    DROP CONSTRAINT daily_dish_item_fk;
ALTER TABLE catalog.family
    DROP CONSTRAINT family_parent_fk;
ALTER TABLE catalog.item
    DROP CONSTRAINT item_family_fk;
ALTER TABLE catalog.item
    DROP CONSTRAINT item_tax_category_fk;
ALTER TABLE catalog.item
    DROP CONSTRAINT item_unit_fk;
ALTER TABLE catalog.item_availability
    DROP CONSTRAINT item_availability_item_fk;
ALTER TABLE catalog.item_availability
    DROP CONSTRAINT item_availability_variant_of_item_fk;
ALTER TABLE catalog.item_price
    DROP CONSTRAINT item_price_item_fk;
ALTER TABLE catalog.item_price
    DROP CONSTRAINT item_price_list_currency_fk;
ALTER TABLE catalog.item_price
    DROP CONSTRAINT item_price_list_fk;
ALTER TABLE catalog.item_price
    DROP CONSTRAINT item_price_variant_of_item_fk;
ALTER TABLE catalog.item_variant
    DROP CONSTRAINT item_variant_item_fk;
ALTER TABLE catalog.macro_entry
    DROP CONSTRAINT macro_entry_item_fk;
ALTER TABLE catalog.macro_entry
    DROP CONSTRAINT macro_entry_macro_fk;
ALTER TABLE catalog.macro_entry
    DROP CONSTRAINT macro_entry_variant_of_item_fk;
ALTER TABLE catalog.menu_group
    DROP CONSTRAINT menu_group_item_fk;
ALTER TABLE catalog.menu_option
    DROP CONSTRAINT menu_option_group_fk;
ALTER TABLE catalog.menu_option
    DROP CONSTRAINT menu_option_item_fk;
ALTER TABLE catalog.menu_option
    DROP CONSTRAINT menu_option_variant_of_item_fk;
ALTER TABLE catalog.quick_page_entry
    DROP CONSTRAINT quick_page_entry_item_fk;
ALTER TABLE catalog.quick_page_entry
    DROP CONSTRAINT quick_page_entry_macro_fk;
ALTER TABLE catalog.quick_page_entry
    DROP CONSTRAINT quick_page_entry_page_fk;
ALTER TABLE catalog.quick_page_entry
    DROP CONSTRAINT quick_page_entry_variant_fk;
ALTER TABLE catalog.store_item_exclusion
    DROP CONSTRAINT store_item_exclusion_item_fk;
ALTER TABLE catalog.tax_rate
    DROP CONSTRAINT tax_rate_category_fk;
ALTER TABLE catalog.automatic_item
    RENAME CONSTRAINT automatic_item_item_fk_tenant TO automatic_item_item_fk;
ALTER TABLE catalog.automatic_item
    RENAME CONSTRAINT automatic_item_variant_of_item_fk_tenant TO automatic_item_variant_of_item_fk;
ALTER TABLE catalog.barcode
    RENAME CONSTRAINT barcode_item_fk_tenant TO barcode_item_fk;
ALTER TABLE catalog.barcode
    RENAME CONSTRAINT barcode_variant_of_item_fk_tenant TO barcode_variant_of_item_fk;
ALTER TABLE catalog.daily_dish
    RENAME CONSTRAINT daily_dish_item_fk_tenant TO daily_dish_item_fk;
ALTER TABLE catalog.family
    RENAME CONSTRAINT family_parent_fk_tenant TO family_parent_fk;
ALTER TABLE catalog.item
    RENAME CONSTRAINT item_family_fk_tenant TO item_family_fk;
ALTER TABLE catalog.item
    RENAME CONSTRAINT item_tax_category_fk_tenant TO item_tax_category_fk;
ALTER TABLE catalog.item
    RENAME CONSTRAINT item_unit_fk_tenant TO item_unit_fk;
ALTER TABLE catalog.item_availability
    RENAME CONSTRAINT item_availability_item_fk_tenant TO item_availability_item_fk;
ALTER TABLE catalog.item_availability
    RENAME CONSTRAINT item_availability_variant_of_item_fk_tenant TO item_availability_variant_of_item_fk;
ALTER TABLE catalog.item_price
    RENAME CONSTRAINT item_price_item_fk_tenant TO item_price_item_fk;
ALTER TABLE catalog.item_price
    RENAME CONSTRAINT item_price_list_currency_fk_tenant TO item_price_list_currency_fk;
ALTER TABLE catalog.item_price
    RENAME CONSTRAINT item_price_list_fk_tenant TO item_price_list_fk;
ALTER TABLE catalog.item_price
    RENAME CONSTRAINT item_price_variant_of_item_fk_tenant TO item_price_variant_of_item_fk;
ALTER TABLE catalog.item_variant
    RENAME CONSTRAINT item_variant_item_fk_tenant TO item_variant_item_fk;
ALTER TABLE catalog.macro_entry
    RENAME CONSTRAINT macro_entry_item_fk_tenant TO macro_entry_item_fk;
ALTER TABLE catalog.macro_entry
    RENAME CONSTRAINT macro_entry_macro_fk_tenant TO macro_entry_macro_fk;
ALTER TABLE catalog.macro_entry
    RENAME CONSTRAINT macro_entry_variant_of_item_fk_tenant TO macro_entry_variant_of_item_fk;
ALTER TABLE catalog.menu_group
    RENAME CONSTRAINT menu_group_item_fk_tenant TO menu_group_item_fk;
ALTER TABLE catalog.menu_option
    RENAME CONSTRAINT menu_option_group_fk_tenant TO menu_option_group_fk;
ALTER TABLE catalog.menu_option
    RENAME CONSTRAINT menu_option_item_fk_tenant TO menu_option_item_fk;
ALTER TABLE catalog.menu_option
    RENAME CONSTRAINT menu_option_variant_of_item_fk_tenant TO menu_option_variant_of_item_fk;
ALTER TABLE catalog.quick_page_entry
    RENAME CONSTRAINT quick_page_entry_item_fk_tenant TO quick_page_entry_item_fk;
ALTER TABLE catalog.quick_page_entry
    RENAME CONSTRAINT quick_page_entry_macro_fk_tenant TO quick_page_entry_macro_fk;
ALTER TABLE catalog.quick_page_entry
    RENAME CONSTRAINT quick_page_entry_page_fk_tenant TO quick_page_entry_page_fk;
ALTER TABLE catalog.quick_page_entry
    RENAME CONSTRAINT quick_page_entry_variant_fk_tenant TO quick_page_entry_variant_fk;
ALTER TABLE catalog.store_item_exclusion
    RENAME CONSTRAINT store_item_exclusion_item_fk_tenant TO store_item_exclusion_item_fk;
ALTER TABLE catalog.tax_rate
    RENAME CONSTRAINT tax_rate_category_fk_tenant TO tax_rate_category_fk;

-- Old unique keys out (nothing references them any more), the new ones take their names.
ALTER TABLE catalog.barcode
    DROP CONSTRAINT barcode_code_uk;
ALTER TABLE catalog.configuration_release
    DROP CONSTRAINT configuration_release_store_version_uk;
ALTER TABLE catalog.family
    DROP CONSTRAINT family_code_uk;
ALTER TABLE catalog.item
    DROP CONSTRAINT item_code_uk;
ALTER TABLE catalog.item_price
    DROP CONSTRAINT item_price_list_item_variant_uk;
ALTER TABLE catalog.item_variant
    DROP CONSTRAINT item_variant_id_item_uk;
ALTER TABLE catalog.item_variant
    DROP CONSTRAINT item_variant_item_code_uk;
ALTER TABLE catalog.macro
    DROP CONSTRAINT macro_code_uk;
ALTER TABLE catalog.menu_group
    DROP CONSTRAINT menu_group_item_code_uk;
ALTER TABLE catalog.menu_option
    DROP CONSTRAINT menu_option_group_item_variant_uk;
ALTER TABLE catalog.price_list
    DROP CONSTRAINT price_list_id_currency_uk;
ALTER TABLE catalog.price_list
    DROP CONSTRAINT price_list_store_uk;
ALTER TABLE catalog.quick_page
    DROP CONSTRAINT quick_page_store_code_uk;
ALTER TABLE catalog.tax_category
    DROP CONSTRAINT tax_category_code_uk;
ALTER TABLE catalog.tax_rate
    DROP CONSTRAINT tax_rate_category_effective_uk;
ALTER TABLE catalog.unit
    DROP CONSTRAINT unit_code_uk;
DROP INDEX catalog.automatic_item_active_ux;
DROP INDEX catalog.configuration_release_one_published_per_store_ux;
DROP INDEX catalog.item_availability_store_item_variant_ux;
ALTER TABLE catalog.barcode
    RENAME CONSTRAINT barcode_code_uk_tenant TO barcode_code_uk;
ALTER TABLE catalog.configuration_release
    RENAME CONSTRAINT configuration_release_store_version_uk_tenant TO configuration_release_store_version_uk;
ALTER TABLE catalog.family
    RENAME CONSTRAINT family_code_uk_tenant TO family_code_uk;
ALTER TABLE catalog.item
    RENAME CONSTRAINT item_code_uk_tenant TO item_code_uk;
ALTER TABLE catalog.item_price
    RENAME CONSTRAINT item_price_list_item_variant_uk_tenant TO item_price_list_item_variant_uk;
ALTER TABLE catalog.item_variant
    RENAME CONSTRAINT item_variant_id_item_uk_tenant TO item_variant_id_item_uk;
ALTER TABLE catalog.item_variant
    RENAME CONSTRAINT item_variant_item_code_uk_tenant TO item_variant_item_code_uk;
ALTER TABLE catalog.macro
    RENAME CONSTRAINT macro_code_uk_tenant TO macro_code_uk;
ALTER TABLE catalog.menu_group
    RENAME CONSTRAINT menu_group_item_code_uk_tenant TO menu_group_item_code_uk;
ALTER TABLE catalog.menu_option
    RENAME CONSTRAINT menu_option_group_item_variant_uk_tenant TO menu_option_group_item_variant_uk;
ALTER TABLE catalog.price_list
    RENAME CONSTRAINT price_list_id_currency_uk_tenant TO price_list_id_currency_uk;
ALTER TABLE catalog.price_list
    RENAME CONSTRAINT price_list_store_uk_tenant TO price_list_store_uk;
ALTER TABLE catalog.quick_page
    RENAME CONSTRAINT quick_page_store_code_uk_tenant TO quick_page_store_code_uk;
ALTER TABLE catalog.tax_category
    RENAME CONSTRAINT tax_category_code_uk_tenant TO tax_category_code_uk;
ALTER TABLE catalog.tax_rate
    RENAME CONSTRAINT tax_rate_category_effective_uk_tenant TO tax_rate_category_effective_uk;
ALTER TABLE catalog.unit
    RENAME CONSTRAINT unit_code_uk_tenant TO unit_code_uk;
ALTER INDEX catalog.automatic_item_active_ux_tenant RENAME TO automatic_item_active_ux;
ALTER INDEX catalog.configuration_release_one_published_per_store_ux_tenant RENAME TO configuration_release_one_published_per_store_ux;
ALTER INDEX catalog.item_availability_store_item_variant_ux_tenant RENAME TO item_availability_store_item_variant_ux;

-- (3b) A table with no id keeps its identity in a primary key that leads with tenant_id (spec: the policy helper
-- asserts it).
ALTER TABLE catalog.daily_dish
    DROP CONSTRAINT daily_dish_pk;
ALTER TABLE catalog.daily_dish
    ADD CONSTRAINT daily_dish_pk PRIMARY KEY (tenant_id, store_id, business_date, item_id);
ALTER TABLE catalog.macro_entry
    DROP CONSTRAINT macro_entry_pk;
ALTER TABLE catalog.macro_entry
    ADD CONSTRAINT macro_entry_pk PRIMARY KEY (tenant_id, macro_id, position);
ALTER TABLE catalog.menu_option
    DROP CONSTRAINT menu_option_pk;
ALTER TABLE catalog.menu_option
    ADD CONSTRAINT menu_option_pk PRIMARY KEY (tenant_id, group_id, position);
ALTER TABLE catalog.quick_page_entry
    DROP CONSTRAINT quick_page_entry_pk;
ALTER TABLE catalog.quick_page_entry
    ADD CONSTRAINT quick_page_entry_pk PRIMARY KEY (tenant_id, page_id, position);
ALTER TABLE catalog.store_item_exclusion
    DROP CONSTRAINT store_item_exclusion_pk;
ALTER TABLE catalog.store_item_exclusion
    ADD CONSTRAINT store_item_exclusion_pk PRIMARY KEY (tenant_id, store_id, item_id);

-- (6) The tenant policy through the helper, then the registries (out of UNSCOPED).
DELETE FROM shared.rls_unscoped_table
WHERE table_name IN ('catalog.barcode'::regclass,
                     'catalog.family'::regclass,
                     'catalog.item'::regclass,
                     'catalog.item_variant'::regclass,
                     'catalog.macro'::regclass,
                     'catalog.macro_entry'::regclass,
                     'catalog.menu_group'::regclass,
                     'catalog.menu_option'::regclass,
                     'catalog.tax_category'::regclass,
                     'catalog.tax_rate'::regclass,
                     'catalog.unit'::regclass);
SELECT shared.rls_protect_tenant('catalog.automatic_item');
SELECT shared.rls_protect_tenant_table('catalog.barcode');
SELECT shared.rls_protect_tenant('catalog.configuration_release');
SELECT shared.rls_protect_tenant('catalog.daily_dish');
SELECT shared.rls_protect_tenant_table('catalog.family');
SELECT shared.rls_protect_tenant_table('catalog.item');
SELECT shared.rls_protect_tenant('catalog.item_availability');
SELECT shared.rls_protect_tenant('catalog.item_price');
SELECT shared.rls_protect_tenant_table('catalog.item_variant');
SELECT shared.rls_protect_tenant_table('catalog.macro');
SELECT shared.rls_protect_tenant_table('catalog.macro_entry');
SELECT shared.rls_protect_tenant_table('catalog.menu_group');
SELECT shared.rls_protect_tenant_table('catalog.menu_option');
SELECT shared.rls_protect_tenant('catalog.price_list');
SELECT shared.rls_protect_tenant('catalog.quick_page');
SELECT shared.rls_protect_tenant('catalog.quick_page_entry');
SELECT shared.rls_protect_tenant('catalog.store_item_exclusion');
SELECT shared.rls_protect_tenant_table('catalog.tax_category');
SELECT shared.rls_protect_tenant_table('catalog.tax_rate');
SELECT shared.rls_protect_tenant_table('catalog.unit');
