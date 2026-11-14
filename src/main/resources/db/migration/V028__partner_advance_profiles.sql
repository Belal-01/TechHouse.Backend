ALTER TABLE partners ADD CONSTRAINT uq_partners_store_id_id UNIQUE (store_id, id);

ALTER TABLE staff_people ADD COLUMN partner_id UUID UNIQUE;
ALTER TABLE staff_people ADD CONSTRAINT fk_staff_person_partner
    FOREIGN KEY (store_id, partner_id) REFERENCES partners(store_id, id);

ALTER TABLE staff_people ALTER COLUMN name DROP NOT NULL;
ALTER TABLE staff_people ADD CONSTRAINT chk_staff_person_profile
    CHECK (partner_id IS NOT NULL OR name IS NOT NULL);

-- Preserve the original advance profile and ledger references when both name and phone match.
UPDATE staff_people s SET partner_id = p.id
FROM partners p
WHERE s.store_id = p.store_id AND lower(s.name) = lower(p.name)
  AND nullif(regexp_replace(s.phone, '[^0-9]', '', 'g'), '') =
      nullif(regexp_replace(p.phone, '[^0-9]', '', 'g'), '');

INSERT INTO staff_people (id, store_id, partner_id)
SELECT p.id, p.store_id, p.id FROM partners p
WHERE NOT EXISTS (SELECT 1 FROM staff_people s WHERE s.partner_id = p.id);

CREATE VIEW v_advance_people AS
SELECT s.id, s.store_id, s.partner_id, p.kind AS partner_kind,
       coalesce(p.name, s.name) AS name,
       CASE WHEN p.id IS NULL THEN s.phone ELSE p.phone END AS phone,
       coalesce(p.active, s.active) AS active,
       s.version
FROM staff_people s LEFT JOIN partners p ON p.id = s.partner_id;
