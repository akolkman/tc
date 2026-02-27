import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.math.*


fun deleteAllFilesInDirectory(path: Path) {
    val dir = path.toFile()

    require(dir.exists() && dir.isDirectory) {
        "Path is not a valid directory"
    }

    dir.listFiles()?.forEach { file ->
        if (file.isFile) {
            if (!file.delete()) {
                throw RuntimeException("Failed to delete ${file.absolutePath}")
            }
        }
    }
}


fun main(args: Array<String>) {
    val cfg = Args.parse(args)

    val inputRoot = cfg.inDir

    Files.createDirectories(cfg.outDir)
    deleteAllFilesInDirectory(cfg.outDir)

    val trimmedRoot = Files.createDirectories(Paths.get("trimmed"))
    deleteAllFilesInDirectory(trimmedRoot)

    require(Files.exists(inputRoot)) {
        "Input directory does not exist: $inputRoot"
    }

    // Walk entire tree
    Files.walk(inputRoot).use { stream ->
        val paths = stream
            .filter { it.isRegularFile() && it.extension.lowercase() == "png" }
            .toList()

        println("Found ${paths.size} PNG files")

        for (path in paths) {
            println(path)
            processImage(path, trimmedRoot, cfg)
        }
    }
}


fun processImage(image: Path, trimmedRoot: Path, cfg: Args) {
    val name = image.nameWithoutExtension
    println("Processing image $name")

    val meta = parseBucketMeta(image)
    println(meta)

    val trimmedImage = trimAndSave(image, trimmedRoot, meta.diaMm, meta.dpi, meta.deg)
    println("Trimmed dimensions = ${trimmedImage.width}, ${trimmedImage.height}")

    println("Degrees from the wrapper = ${meta.deg}")

    val rad = Math.toRadians(meta.deg)

    println("Radians from the wrapper = $rad")

    val base = SinCos(sin(rad), cos(rad))

    println("Sin = ${base.s} and Cos = ${base.c}")

    val dxValues =  generateDxStratified(
            width = trimmedImage.width,
            angle = meta.deg,
            maxCorrectionAngle = 125.0,
            bins = cfg.bins,
            perBin = cfg.perBin,
            seed = cfg.seed
        )

    val total = dxValues.size
    println("Generating $total samples from ${image.name} -> ${cfg.outDir.name}")
    println("Base label: s0=${base.s}, c0=${base.c}, resized=${trimmedImage.width}x${trimmedImage.height}")

    dxValues.forEachIndexed { idx, dx ->
        val shiftedImage = circularShiftX(trimmedImage, dx)
        val label = adjustLabelAfterShift(base, dx, trimmedImage.width)

        val name = buildOutputName(
            baseName = name,
            label = label,
            id = idx,
            scale = cfg.labelScale
        )

        val grayscaleImage = toGrayscale(shiftedImage)
        val resizedImage = resizeGray(grayscaleImage, cfg.targetW, cfg.targetH)

        val outputPath = cfg.outDir.resolve(name)
        ImageIO.write(resizedImage, "png", outputPath.toFile())
    }

    val grayscaleImage = toGrayscale(trimmedImage)
    val resizedImage = resizeGray(grayscaleImage, cfg.targetW, cfg.targetH)

    val sx = (base.s * cfg.labelScale).roundToInt()
    val cx = (base.c * cfg.labelScale).roundToInt()

    val fileName =  buildString {
        append(name)
        append("__sx"); append(fmtSignedInt(sx))
        append("__cx"); append(fmtSignedInt(cx))
        append("__original")
        append(".png")
    }

    val outputPath = cfg.outDir.resolve(fileName)
    ImageIO.write(resizedImage, "png", outputPath.toFile())
}


fun trimAndSave(
    image: Path,
    trimmedRoot: Path,
    diaMm: Double,
    dpi: Double,
    angle: Double
): BufferedImage {
    val img = ImageIO.read(image.toFile())
        ?: error("Could not read image: $image")

    val targetWidth = visibleWidthPx(diaMm, dpi)

    println("Target width: $targetWidth")

    // If artwork is narrower than expected, fail fast so you notice bad inputs
    if (targetWidth > img.width) {
        error("Computed targetWidth=$targetWidth px > imageWidth=${img.width} for $image (dia=$diaMm, dpi=$dpi)")
    }

    val trimmedImage = cropRight(img, targetWidth)

    val outputPath = trimmedRoot.resolve(image.nameWithoutExtension + "__${angle.toInt()}.png")

    val ok = ImageIO.write(trimmedImage, "png", outputPath.toFile())
    if (!ok) error("No ImageIO writer found for PNG when writing: $outputPath")

    return trimmedImage
}


fun visibleWidthPx(diaMm: Double, dpi: Double): Int {
    // V_px = π * D_mm * (dpi / 25.4)
    val inches = (PI * diaMm) / 25.4
    return (inches * dpi).roundToInt()
}


fun cropRight(img: BufferedImage, targetWidth: Int): BufferedImage {
    require(targetWidth > 0) { "targetWidth must be > 0" }
    require(targetWidth <= img.width) {
        "targetWidth=$targetWidth exceeds image width=${img.width}"
    }
    return img.getSubimage(0, 0, targetWidth, img.height)
}


data class SinCos(val s: Double, val c: Double) {
    fun normalized(): SinCos {
        val r = hypot(s, c)
        if (r == 0.0) error("Invalid sin/cos: both zero.")
        return SinCos(s / r, c / r)
    }
}


data class Args(
    val inDir: Path,
    val outDir: Path,
    val targetW: Int,
    val targetH: Int,
    val nSamples: Int,
    val bins: Int,
    val perBin: Int,
    val seed: Long,
    val labelScale: Int
) {
    companion object {
        fun parse(argv: Array<String>): Args {
            fun get(flag: String): String? {
                val i = argv.indexOf(flag)
                if (i < 0) return null
                if (i + 1 >= argv.size) error("Missing value after $flag")
                return argv[i + 1]
            }

            val inPath = get("--in") ?: error("Missing --in <file>")
            val outPath = get("--out") ?: error("Missing --out <dir>")

            val w = (get("--w") ?: "512").toInt()
            val h = (get("--h") ?: "256").toInt()

            val n = (get("--n") ?: "120").toInt()

            val bins = (get("--bins") ?: "36").toInt()
            val perBin = (get("--perBin") ?: "3").toInt()

            val seed = (get("--seed") ?: "42").toLong()

            val scale = (get("--scale") ?: "100000").toInt()

            return Args(
                inDir = Path.of(inPath),
                outDir = Path.of(outPath),
                targetW = w,
                targetH = h,
                nSamples = n,
                bins = bins,
                perBin = perBin,
                seed = seed,
                labelScale = scale
            )
        }
    }
}


/** Convert to 8-bit grayscale. */
fun toGrayscale(src: BufferedImage): BufferedImage {
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_BYTE_GRAY)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.drawImage(src, 0, 0, null)
    g.dispose()
    return out
}


/** Resize with bilinear interpolation. */
fun resizeGray(srcGray: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
    require(srcGray.type == BufferedImage.TYPE_BYTE_GRAY) {
        "Expected grayscale (TYPE_BYTE_GRAY) image"
    }

    val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(srcGray, 0, 0, targetW, targetH, null)
    g.dispose()
    return out
}

/**
 * Circularly shift horizontally by dx pixels.
 * Positive dx = shift RIGHT (pixels wrap from right edge to left edge).
 */
fun circularShiftX(src: BufferedImage, dx: Int): BufferedImage {
    val w = src.width
    val h = src.height
    val out = BufferedImage(w, h, src.type)

    val s = ((dx % w) + w) % w // normalize to [0, w)
    val g = out.createGraphics()

    // Copy [0, w-s) to [s, w)
    g.drawImage(src.getSubimage(0, 0, w - s, h), s, 0, null)
    // Copy [w-s, w) to [0, s)
    g.drawImage(src.getSubimage(w - s, 0, s, h), 0, 0, null)

    g.dispose()
    return out
}

/**
 * Update sin/cos label after shifting image by dx pixels.
 *
 * delta = -dx * 2π / W  (matches earlier convention: degrees = -dx * 360 / W)
 * s = s0*cos(delta) + c0*sin(delta)
 * c = c0*cos(delta) - s0*sin(delta)
 */
fun adjustLabelAfterShift(base: SinCos, dx: Int, widthPx: Int): SinCos {
    val delta = -dx.toDouble() * (2.0 * Math.PI) / widthPx.toDouble()
    val sd = sin(delta)
    val cd = cos(delta)

    val s = base.s * cd + base.c * sd
    val c = base.c * cd - base.s * sd
    return SinCos(s, c).normalized()
}

/** Output naming that’s easy to parse in Python. */
fun buildOutputName(baseName: String, label: SinCos, id: Int, scale: Int): String {
    val sx = (label.s * scale).roundToInt()
    val cx = (label.c * scale).roundToInt()

    return buildString {
        append(baseName)
        append("__sx"); append(fmtSignedInt(sx))
        append("__cx"); append(fmtSignedInt(cx))
        append("__id"); append(id.toString().padStart(6, '0'))
        append(".png")
    }
}


fun fmtSignedInt(v: Int, width: Int = 6): String =
    if (v >= 0) "+${v.toString().padStart(width, '0')}" else "-${(-v).toString().padStart(width, '0')}"


/**
 * STRATIFIED dx values:
 * Split [0, W) into bins and sample 'perBin' random dx per bin.
 * Then map to signed range roughly centered around 0 (optional but convenient).
 */
fun generateDxStratified(width: Int, angle: Double, maxCorrectionAngle: Double, bins: Int, perBin: Int, seed: Long): List<Int> {
    require(bins > 0) { "bins must be > 0" }
    require(perBin > 0) { "perBin must be > 0" }

    println("Width = $width, Angle = $angle, MaxCorrectionAngle = $maxCorrectionAngle, PerBin = $perBin, Seed = $seed")

    val validCorrectionToLeft = 111 - angle
    val validCorrectionToRight = 111 + angle

    val validCorrectionToLeftPixels = floor(width * (validCorrectionToLeft / 360.0)).toInt()
    val validCorrectionToRightPixels = floor(width * (validCorrectionToRight / 360.0)).toInt()

    val range = validCorrectionToLeftPixels + validCorrectionToRightPixels

    println("ValidCorrectionLeftPixels = $validCorrectionToLeftPixels, ValidCorrectionRightPixels = $validCorrectionToRightPixels")

    val rng = kotlin.random.Random(seed)
    val binSize = max(1, range / bins)

    println("BinSize = $binSize")

    val dxs = mutableListOf<Int>()
    for (b in 0 until bins) {
        val start = (width / 2 - validCorrectionToLeftPixels) + b * binSize
        val end = start + binSize

        println("Start = $start, End = $end --- Width = $width")

        if (start >= end) continue

        repeat(perBin) {
            // val dx0 = start + rng.nextInt(end - start)
            // val dx0 = start + binSize
            val dx0 = start + 0

            // Convert to signed range around 0 for nicer dx values (optional)
            val signed = dx0 - width / 2

            dxs.add(signed)
        }
    }
    // dxs.shuffle(rng)

    // exitProcess(0)

    return dxs
}