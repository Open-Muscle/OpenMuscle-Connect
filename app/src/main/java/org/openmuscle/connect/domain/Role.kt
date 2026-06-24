package org.openmuscle.connect.domain

/**
 * A hub-assigned role for a subscribed source (PROTOCOL.md 8.1). Sources are
 * role-agnostic; the hub decides which band is left/right and which device is the
 * labeler. The role is hub-local state, persisted per `device_id`, and written
 * into the schema-v2 capture CSV's `role` column (docs/CSV-SCHEMA-V2.md).
 *
 * [wire] is the canonical lowercase token used in the CSV and on any future wire
 * use; v1.1 reserves `reference` / `auxiliary`.
 */
enum class Role(val wire: String) {
    LEFT("left"),
    RIGHT("right"),
    LABELER("labeler");

    companion object {
        fun fromWire(s: String?): Role? = entries.firstOrNull { it.wire == s }
    }
}
