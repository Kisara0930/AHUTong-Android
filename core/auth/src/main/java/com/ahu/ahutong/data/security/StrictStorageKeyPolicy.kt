package com.ahu.ahutong.data.security

/** Never introduce a fresh key while any value still belongs to a missing old key. */
internal fun <T : Any> resolveStrictStorageKey(existing: T?, hasStoredValues: Boolean, create: () -> T): T {
    if (existing != null) return existing
    if (hasStoredValues) throw java.security.KeyStoreException("Storage key missing while encrypted values remain; original data retained")
    return create()
}
