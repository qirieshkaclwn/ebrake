package android.net

/**
 * Тестовая реализация [Uri] для unit-тестов JVM без Robolectric.
 * Находится в пакете android.net для доступа к package-private конструктору Uri().
 */
class FakeUri(private val uriString: String = "content://media/1") : Uri() {
    override fun toString(): String = uriString
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "content"
    override fun getSchemeSpecificPart(): String = uriString.removePrefix("content:")
    override fun getEncodedSchemeSpecificPart(): String = getSchemeSpecificPart()
    override fun getAuthority(): String = "media"
    override fun getEncodedAuthority(): String = "media"
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = "media"
    override fun getPort(): Int = -1
    override fun getPath(): String = "/1"
    override fun getEncodedPath(): String = "/1"
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = listOf("1")
    override fun getLastPathSegment(): String = "1"
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
    override fun compareTo(other: Uri?): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Uri) return false
        return toString() == other.toString()
    }

    override fun hashCode(): Int = toString().hashCode()
}
