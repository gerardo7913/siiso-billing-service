package com.siiso.billing.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/** UUID ⇄ {@code BINARY(16)} for the import's plain SQL. */
public final class UuidBytes {

    private UuidBytes() {
    }

    /** @return {@code null} for a {@code null} id (e.g. no author: the legacy import) */
    public static byte[] of(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).array();
    }

    public static UUID toUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
