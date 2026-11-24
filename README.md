# siiso-billing-service

Facturación de SIISO. Por ahora: **planes de tarifas** (Diamante, Oro… o los precios propios de una
empresa) con precios por vigencia, y el precio que paga cada empresa por cada servicio del portafolio
(siiso-docs ADR 0022). Después: facturas, RIPS y cartera.

- API: `/api/v1/billing` (puerto 8085), solo portal Colaborador; permisos `RATE_PLANS` y `ORGANIZATION_RATES`.
- Pantallas: `siiso-master-data-web` → Facturación → Planes de tarifas / Tarifas por empresa.
- Datos: tablas `billing_*` de `siiso_business` (`siiso-business-db` V007).
- Mientras el escritorio siga en uso, cada cambio se replica en `tarifa` de la base anterior.

```bash
cp .env.example .env   # y completar
./mvnw spring-boot:run
./mvnw test            # MySQL 9.7 y 5.7 con Testcontainers (Docker)
```
