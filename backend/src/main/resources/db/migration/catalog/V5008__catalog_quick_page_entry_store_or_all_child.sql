-- A quick-page entry follows its STORE_OR_ALL page (D168 amended by S70): V5007's hand-written FOR ALL policy let a
-- store scope DELETE the entries of an every-store page. The shared helper (V1009) now writes it: read when the page
-- is every-store or in scope, inserted, updated and deleted only when the page is in scope.
DROP POLICY store_or_all_parent_scope ON catalog.quick_page_entry;
DROP TRIGGER parent_fixed ON catalog.quick_page_entry;
SELECT shared.rls_protect_store_or_all_child_table('catalog.quick_page_entry', 'page_id', 'catalog.quick_page', 'id');
