package com.tkjone.app.config


/**
 * Centralized config — all URLs obfuscated via char arrays + runtime build.
 * Hard to find in smali via simple string search.
 */
object AppConfig {

    // ─── Base URL (built at runtime from char arrays) ───
    private val _baseHost = charArrayOf('t','k','j','1','.','p','a','g','e','s','.','d','e','v')
    val BASE_HOST: String get() = String(_baseHost)
    val BASE_URL: String get() = "https://$BASE_HOST"
    val PHOTO_LINK_PREFIX: String get() = "$BASE_URL/photo/?name="
    val UPLOAD_URL: String get() = "$BASE_URL/upload"
    val UPLOADERS_URL: String get() = "$BASE_URL/uploaders"
    val PRIVACY_URL: String get() = "$BASE_URL/privacy"
    val TERMS_URL: String get() = "$BASE_URL/terms"

    // ─── API Endpoints (obfuscated) ───
    private val _dbPath = charArrayOf('/','D','B','/')
    private val _photosJson = charArrayOf('p','h','o','t','o','s','.','j','s','o','n')
    private val _infoJson = charArrayOf('i','n','f','o','.','j','s','o','n')
    private val _statusJson = charArrayOf('s','t','a','t','u','s','.','j','s','o','n')

    val PHOTOS_JSON_URL: String get() = "$BASE_URL${String(_dbPath)}${String(_photosJson)}"
    val INFO_JSON_URL: String get() = "$BASE_URL${String(_dbPath)}${String(_infoJson)}"
    val STATUS_JSON_URL: String get() = "$BASE_URL${String(_dbPath)}${String(_statusJson)}"

    // ─── Deep link host (for matching incoming URIs) ───
    val DEEP_LINK_HOST: String get() = BASE_HOST

    // ─── GitHub avatar (obfuscated) ───
    private val _ghAvatar = charArrayOf('h','t','t','p','s',':','/','/','w','w','w','.','g','i','t','h','u','b','.','c','o','m','/','J','i','r','a','n','k','u','n','.','p','n','g')
    val GITHUB_AVATAR_URL: String get() = String(_ghAvatar)

    // ─── Portfolio link (obfuscated) ───
    private val _portfolio = charArrayOf('h','t','t','p','s',':','/','/','z','h','y','l','l','a','n','f','y','l','l','a','h','.','m','y','.','i','d')
    val PORTFOLIO_URL: String get() = String(_portfolio)

    // ─── DNS over HTTPS URL (obfuscated) ───
    private val _dohUrl = charArrayOf('h','t','t','p','s',':','/','/','c','l','o','u','d','f','l','a','r','e','-','d','n','s','.','c','o','m','/','d','n','s','-','q','u','e','r','y')
    val DOH_URL: String get() = String(_dohUrl)

    // ─── Photo link builder ───
    fun photoLink(name: String): String = "$PHOTO_LINK_PREFIX" + java.net.URLEncoder.encode(name, "UTF-8")
}
