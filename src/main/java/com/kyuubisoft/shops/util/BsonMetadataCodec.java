package com.kyuubisoft.shops.util;

import org.bson.BsonDocument;

/**
 * Serialisation helper for ItemStack BSON metadata.
 *
 * <p>Encodes {@link BsonDocument} values into a string for SQL TEXT column
 * storage, and decodes them back when an item is restored from the DB.
 * Uses the MongoDB Extended JSON format (the default mode of
 * {@link BsonDocument#toJson()}). This preserves every BSON type cleanly
 * across save/load, including nested arrays, int32/int64 distinction,
 * binary blobs and doubles.</p>
 *
 * <p>Used by the shop pipeline to preserve enchantments, custom stats,
 * pet IDs, weapon mastery levels and every other mod-specific BSON field
 * through capture (owner drops an item into the shop grid) -> persist
 * (shop_items / shop_mailbox TEXT columns) -> restore (buyer receives
 * via {@code ShopService.giveItem} or mailbox claim).</p>
 *
 * <p>All methods are null-tolerant: empty or unparseable inputs return
 * {@code null} so the caller can fall back to a vanilla
 * {@code new ItemStack(itemId, quantity)} constructor without metadata.</p>
 */
public final class BsonMetadataCodec {

    private BsonMetadataCodec() {}

    /**
     * Encodes a {@link BsonDocument} to a JSON string for DB persistence.
     *
     * @param meta the metadata to encode — may be {@code null} or empty
     * @return the JSON representation, or {@code null} if {@code meta} is
     *         null/empty or encoding failed
     */
    public static String encode(BsonDocument meta) {
        if (meta == null || meta.isEmpty()) return null;
        try {
            return meta.toJson();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a stored JSON string back into a {@link BsonDocument}.
     *
     * @param json the serialised metadata — may be {@code null} or blank
     * @return the parsed document, or {@code null} if {@code json} is
     *         null/blank or could not be parsed (legacy rows, corruption)
     */
    public static BsonDocument decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return BsonDocument.parse(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convenience: strips any DTT virtual-ID suffix from an item id so the
     * canonical id is written to the database. Virtual ids carry cached
     * state in the id itself (separator: {@code __}); the canonical id is
     * everything before the first separator.
     *
     * @param itemId the raw (possibly virtual) item id
     * @return the canonical item id — never contains {@code __}
     */
    public static String stripDttSuffix(String itemId) {
        if (itemId == null) return null;
        int idx = itemId.indexOf("__");
        return (idx > 0) ? itemId.substring(0, idx) : itemId;
    }
}
