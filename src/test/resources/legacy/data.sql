-- Synthetic legacy rates; each row exercises a rule of the import / write-back.
-- 0002 and 0003 depend on 0001 (the desktop app prices them with 0001's rows); 0080 has its own list.
INSERT INTO empresa VALUES ('0001', 'EMPRESA PRESTADORA DE SERVICIO', '0001'), ('0002', 'AB&C', '0001'),
                           ('0003', 'GIMNASIO ALTAIR', '0001'), ('0080', 'AGUAGER S.A', '0080'),
                           ('0081', 'EMPRESA SIN TARIFA', '0081');
INSERT INTO tarifa VALUES
    ('4001', '0001', '1', 40000.00, '1', '0', '1'),
    ('1001', '0001', '1', 18000.00, '1', '0', '1'),
    ('1002', '0001', '1', 9000.00, '1', '0', '0'),     -- disabled: not imported
    ('1001', '0002', '1', 17000.00, '1', '0', '1'),    -- own row of a dependent company: unused
    ('4001', '0080', '1', 35000.00, '1', '0', '1'),
    ('7777', '0080', '1', 1000.00, '1', '0', '1'),     -- unknown service
    ('4001', '9999', '1', 30000.00, '1', '0', '1');    -- not a company (Misión)

-- Moderator fee and copay by company and level; level 9 does not exist.
INSERT INTO descuento VALUES ('1', '0080', 3200, 2), ('2', '0080', 6100, 4), ('9', '0080', 1, 1);
