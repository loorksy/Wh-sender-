package cloud.lork.mursal.data

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val force: Boolean,
    val apkUrl: String,
    val title: String,
    val changelog: List<String>,
)
