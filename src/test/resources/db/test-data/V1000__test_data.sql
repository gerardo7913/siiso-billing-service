-- Test only: catalogs, services and companies the tests use (their owner services import the real ones).
INSERT INTO catalog_rate_schedule (code, name) VALUES ('1', 'TARIFA REDUCIDA'), ('2', 'ISS');
INSERT INTO catalog_procedure_category (code, name) VALUES ('02', 'Laboratorios clínicos'), ('04', 'Audiometría');
INSERT INTO service_offering (code, name, category_id, status)
SELECT '4001', 'Audiometría tonal', id, 'ACTIVE' FROM catalog_procedure_category WHERE code = '04';
INSERT INTO service_offering (code, name, category_id, status)
SELECT '1001', 'Hemograma', id, 'ACTIVE' FROM catalog_procedure_category WHERE code = '02';
INSERT INTO service_offering (code, name, category_id, status)
SELECT '1002', 'Glicemia', id, 'ACTIVE' FROM catalog_procedure_category WHERE code = '02';
INSERT INTO service_offering (code, name, category_id, status)
SELECT '1003', 'Prueba retirada', id, 'INACTIVE' FROM catalog_procedure_category WHERE code = '02';
INSERT INTO organization (id, code, legal_name) VALUES
    (1, '0001', 'EMPRESA PRESTADORA DE SERVICIO'),
    (2, '0002', 'AB&C'),
    (3, '0003', 'GIMNASIO ALTAIR'),
    (80, '0080', 'AGUAGER S.A'),
    (81, '0081', 'EMPRESA SIN TARIFA');
