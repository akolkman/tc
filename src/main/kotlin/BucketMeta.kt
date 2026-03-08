import java.nio.file.Path

private val DIR_RE = Regex(
    """^(.+?)__\(dia=([0-9]+(?:\.[0-9]+)?),dpi=([0-9]+(?:\.[0-9]+)?),deg=([+-]?[0-9]+(?:\.[0-9]+)?)\)$"""
)

data class BucketMeta(
    val bucket: String,
    val diaMm: Double,
    val dpi: Double,
    val deg: Double
) {
    val clusterName: String
        get() = "${bucket}_${diaMm.toInt()}"
}

fun parseBucketMeta(imagePath: Path): BucketMeta {
    val parent = imagePath.parent?.fileName?.toString()
        ?: error("No parent directory for $imagePath")

    val m = DIR_RE.matchEntire(parent)
        ?: error("Directory name does not match expected pattern: '$parent'")

    return BucketMeta(
        bucket = m.groupValues[1],
        diaMm = m.groupValues[2].toDouble(),
        dpi = m.groupValues[3].toDouble(),
        deg = m.groupValues[4].toDouble()
    )
}
