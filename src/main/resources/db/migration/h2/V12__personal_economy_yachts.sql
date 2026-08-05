INSERT INTO asset_catalog_item
    (code, asset_type, apartment_rooms, name, icon_key, purchase_price, resale_haircut_bps, active, version)
SELECT 'YACHT_CLASSIC', 'YACHT', NULL, 'Classic Yacht', 'yacht-classic', 4500000, 2500, TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM asset_catalog_item WHERE code = 'YACHT_CLASSIC');

INSERT INTO asset_catalog_item
    (code, asset_type, apartment_rooms, name, icon_key, purchase_price, resale_haircut_bps, active, version)
SELECT 'YACHT_SUPER', 'YACHT', NULL, 'Super Yacht', 'yacht-super', 28000000, 3000, TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM asset_catalog_item WHERE code = 'YACHT_SUPER');
