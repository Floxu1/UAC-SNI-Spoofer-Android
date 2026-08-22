package com.uacspoofer.mobile.profiles

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ProfileImportResult(
    val library: ProfileLibrary,
    val importedCount: Int,
    val errors: List<String>,
)


class ProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): ProfileLibrary {
        migrateLegacyOnce()
        val profiles = readProfiles()
        val requested = prefs.getString(KEY_SELECTED, ProxyProfile.BUILT_IN_ID) ?: ProxyProfile.BUILT_IN_ID
        val selected = requested.takeIf { id ->
            ProxyProfile.isProtectedBuiltIn(id) || profiles.any { it.id == id }
        } ?: ProxyProfile.BUILT_IN_ID
        if (selected != requested) prefs.edit().putString(KEY_SELECTED, selected).apply()
        return ProfileLibrary(profiles, selected)
    }

    @Synchronized
    fun select(id: String): ProfileLibrary {
        val current = snapshot()
        require(current.allProfiles.any { it.id == id }) { "Profile is no longer available" }
        prefs.edit().putString(KEY_SELECTED, id).apply()
        return current.copy(selectedId = id)
    }

    @Synchronized
    fun importText(text: String, nameOverride: String? = null): ProfileImportResult {
        val candidates = ProfileUriParser.extractUris(text).ifEmpty {
            if (
                text.trim().startsWith("vless://", true) ||
                text.trim().startsWith("trojan://", true) ||
                text.trim().startsWith("vmess://", true)
            ) {
                listOf(text.trim())
            } else {
                emptyList()
            }
        }
        if (candidates.isEmpty()) {
            return ProfileImportResult(snapshot(), 0, listOf("No VLESS, Trojan or VMess URI found"))
        }
        val current = snapshot().customProfiles.toMutableList()
        val errors = mutableListOf<String>()
        var imported = 0
        candidates.forEachIndexed { index, raw ->
            runCatching {
                ProfileUriParser.parse(raw, nameOverride = nameOverride.takeIf { candidates.size == 1 })
            }.onSuccess { profile ->
                current.removeAll { existing ->
                    !existing.isBuiltIn && ProfileUriParser.canonicalUri(existing) == ProfileUriParser.canonicalUri(profile)
                }
                current.add(0, profile)
                imported++
            }.onFailure { error ->
                errors += "Item ${index + 1}: ${error.message ?: "invalid configuration"}"
            }
        }
        if (imported > 0) writeProfiles(current)
        return ProfileImportResult(snapshot(), imported, errors)
    }

    @Synchronized
    fun importProfiles(profiles: List<ProxyProfile>): ProfileImportResult {
        if (profiles.isEmpty()) return ProfileImportResult(snapshot(), 0, emptyList())
        val current = snapshot().customProfiles.toMutableList()
        var imported = 0
        profiles.asReversed().forEach { profile ->
            if (profile.isBuiltIn) return@forEach
            val canonical = ProfileUriParser.canonicalUri(profile)
            current.removeAll { existing ->
                existing.id == profile.id || ProfileUriParser.canonicalUri(existing) == canonical
            }
            current.add(0, profile)
            imported++
        }
        if (imported > 0) writeProfiles(current)
        return ProfileImportResult(snapshot(), imported, emptyList())
    }

    @Synchronized
    fun update(id: String, rawUri: String, name: String): ProfileLibrary {
        require(!ProxyProfile.isProtectedBuiltIn(id)) { "Built-in profile is read-only" }
        val current = snapshot().customProfiles.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        require(index >= 0) { "Profile is no longer available" }
        current[index] = ProfileUriParser.parse(rawUri, id = id, nameOverride = name)
        writeProfiles(current)
        return snapshot()
    }

    @Synchronized
    fun updateCountry(id: String, country: CountryMetadata): ProfileLibrary {
        require(!ProxyProfile.isProtectedBuiltIn(id)) { "Built-in profile is read-only" }
        if (!country.isKnown) return snapshot()
        val current = snapshot().customProfiles.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        require(index >= 0) { "Profile is no longer available" }
        if (current[index].country == country) return snapshot()
        current[index] = current[index].copy(country = country)
        writeProfiles(current)
        return snapshot()
    }

    @Synchronized
    fun delete(id: String): ProfileLibrary {
        return deleteMany(setOf(id))
    }

    @Synchronized
    fun deleteMany(ids: Set<String>): ProfileLibrary {
        require(ProxyProfile.BUILT_IN_ID !in ids && ProxyProfile.BUILT_IN_2_ID !in ids) { "Built-in profile is read-only" }
        if (ids.isEmpty()) return snapshot()
        val before = snapshot()
        val remaining = before.customProfiles.filterNot { it.id in ids }
        writeProfiles(remaining)
        if (before.selectedId in ids) prefs.edit().putString(KEY_SELECTED, ProxyProfile.BUILT_IN_ID).apply()
        return snapshot()
    }

    fun selectedProfile(): ProxyProfile = snapshot().selectedProfile

    @Synchronized
    fun markActive(id: String, endpoint: ProfileEndpoint) {
        prefs.edit()
            .putString(KEY_ACTIVE, id)
            .putString(KEY_ACTIVE_HOST, endpoint.host)
            .putInt(KEY_ACTIVE_PORT, endpoint.port)
            .apply()
    }

    @Synchronized
    fun clearActive() {
        prefs.edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_ACTIVE_HOST)
            .remove(KEY_ACTIVE_PORT)
            .apply()
    }

    fun activeProfile(): ProxyProfile? {
        val id = prefs.getString(KEY_ACTIVE, null) ?: return null
        return snapshot().allProfiles.firstOrNull { it.id == id }
    }

    fun activeEndpoint(): ProfileEndpoint? {
        val host = prefs.getString(KEY_ACTIVE_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_ACTIVE_PORT, 0)
        return if (host.isNotBlank() && port in 1..65_535) ProfileEndpoint(host, port) else null
    }

    private fun readProfiles(): List<ProxyProfile> = runCatching {
        val array = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = item.optString("id")
                val uri = item.optString("uri")
                val name = item.optString("name")
                val storedCountry = CountryMetadata.resolve(
                    item.optString("countryCode"),
                    item.optString("countryName"),
                )
                runCatching {
                    ProfileUriParser.parse(uri, id = id, nameOverride = name).let { profile ->
                        if (storedCountry.isKnown) profile.copy(country = storedCountry) else profile
                    }
                }
                    .getOrNull()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun writeProfiles(profiles: List<ProxyProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            val persistedUri = profile.rawUri
                .takeIf { ProfileUriParser.extractUris(it).isNotEmpty() }
                ?: ProfileUriParser.canonicalUri(profile)
            val item = JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("uri", persistedUri)
            if (profile.country.isKnown) {
                item.put("countryCode", profile.country.countryCode)
                item.put("countryName", profile.country.countryName)
            }
            array.put(item)
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun migrateLegacyOnce() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val selectedLegacy = legacy.getLong("selected", -1L)
        val migrated = mutableListOf<ProxyProfile>()
        var migratedSelection: String? = null
        runCatching {
            val old = JSONArray(legacy.getString("profiles", "[]") ?: "[]")
            repeat(old.length()) { index ->
                val item = old.optJSONObject(index) ?: return@repeat
                val oldId = item.optLong("id", -1L)
                val oldName = item.optString("name")
                val firstUri = ProfileUriParser.extractUris(item.optString("content")).firstOrNull() ?: return@repeat
                val newId = "legacy:$oldId"
                runCatching { ProfileUriParser.parse(firstUri, id = newId, nameOverride = oldName) }
                    .onSuccess {
                        migrated += it
                        if (oldId == selectedLegacy) migratedSelection = newId
                    }
            }
        }
        if (migrated.isNotEmpty() && readProfiles().isEmpty()) writeProfiles(migrated)
        prefs.edit()
            .putBoolean(KEY_MIGRATED, true)
            .apply {
                migratedSelection?.let { putString(KEY_SELECTED, it) }
            }
            .apply()
    }

    companion object {
        private const val PREFS = "uac_proxy_profiles_v2"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_SELECTED = "selected"
        private const val KEY_ACTIVE = "active"
        private const val KEY_ACTIVE_HOST = "active_host"
        private const val KEY_ACTIVE_PORT = "active_port"
        private const val KEY_MIGRATED = "legacy_migrated_v1"
        private const val LEGACY_PREFS = "uac_local_configs"
    }
}
