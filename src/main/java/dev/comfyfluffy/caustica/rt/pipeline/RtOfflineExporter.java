package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.OfflineGroundTruth;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;

/** One-shot, world-only export of the completed offline mean and its fixed-exposure display mapping. */
public final class RtOfflineExporter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int EXR_MAGIC = 20000630;

    private RtOfflineExporter() {
    }

    public static CompletableFuture<ExportResult> exportAsync(RtContext ctx, RtImage mean, RtImage display) {
        if (mean.width != display.width || mean.height != display.height) {
            throw new IllegalArgumentException("Offline mean/display extent mismatch");
        }
        long meanBytes = Math.multiplyExact((long) mean.width * mean.height, 16L);
        long displayBytes = Math.multiplyExact((long) display.width * display.height, 4L);
        RtBuffer meanReadback = ctx.createBuffer(meanBytes, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true,
                "offline EXR readback");
        boolean savePng = CausticaConfig.Rt.Offline.SAVE_PNG.value();
        boolean saveExr = CausticaConfig.Rt.Offline.SAVE_EXR.value();
        ExportMetadata metadata = new ExportMetadata(
                CausticaConfig.Rt.Offline.SAMPLES_PER_BATCH.value(),
                CausticaConfig.Rt.Offline.MIN_SAMPLES.value(),
                CausticaConfig.Rt.Offline.MAX_SAMPLES.value(),
                CausticaConfig.Rt.Offline.MAX_BOUNCES.value(),
                CausticaConfig.Rt.Offline.ADAPTIVE.value(),
                CausticaConfig.Rt.Offline.RELATIVE_ERROR.value(),
                CausticaConfig.Rt.Offline.ABSOLUTE_ERROR.value(),
                OfflineGroundTruth.INSTANCE.convergedPixels(),
                OfflineGroundTruth.INSTANCE.elapsedSeconds(),
                CausticaConfig.offlineRenderSignature());
        RtBuffer displayReadback = savePng ? ctx.createBuffer(displayBytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, "offline PNG readback") : null;
        try {
            ctx.waitIdle("offline renderer export snapshot");
            ctx.submitSync(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    VK10.vkCmdCopyImageToBuffer(cmd, mean.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            meanReadback.handle, copyRegion(stack, mean.width, mean.height));
                    if (displayReadback != null) {
                        VK10.vkCmdCopyImageToBuffer(cmd, display.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                                displayReadback.handle, copyRegion(stack, display.width, display.height));
                    }
                }
            });
            Vma.vmaInvalidateAllocation(ctx.vma(), meanReadback.allocation, 0, meanBytes);
            if (displayReadback != null) {
                Vma.vmaInvalidateAllocation(ctx.vma(), displayReadback.allocation, 0, displayBytes);
            }
            byte[] meanCopy = new byte[Math.toIntExact(meanBytes)];
            MemoryUtil.memByteBuffer(meanReadback.mapped, meanCopy.length).get(meanCopy);
            byte[] displayCopy = null;
            if (displayReadback != null) {
                displayCopy = new byte[Math.toIntExact(displayBytes)];
                MemoryUtil.memByteBuffer(displayReadback.mapped, displayCopy.length).get(displayCopy);
            }
            byte[] finalDisplayCopy = displayCopy;
            Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Path directory = gameDirectory.resolve("screenshots").resolve("caustica-offline");
                    Files.createDirectories(directory);
                    String base = "caustica-offline-" + LocalDateTime.now().format(FILE_TIME);
                    Path exr = saveExr ? directory.resolve(base + ".exr") : null;
                    Path png = savePng ? directory.resolve(base + ".png") : null;
                    Path manifest = directory.resolve(base + ".json");
                    ByteBuffer meanPixels = ByteBuffer.wrap(meanCopy).order(ByteOrder.nativeOrder());
                    if (exr != null) {
                        writeExr(exr, meanPixels, mean.width, mean.height);
                    }
                    if (png != null) {
                        writePng(png, ByteBuffer.wrap(finalDisplayCopy), display.width, display.height);
                    }
                    SampleStats stats = sampleStats(meanPixels, mean.width, mean.height);
                    writeManifest(manifest, exr, png, mean.width, mean.height, stats, metadata);
                    return new ExportResult(exr, png, manifest);
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        } finally {
            meanReadback.destroy();
            if (displayReadback != null) {
                displayReadback.destroy();
            }
        }
    }

    private static VkBufferImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
        region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).imageOffset().set(0, 0, 0);
        region.get(0).imageExtent().set(width, height, 1);
        return region;
    }

    private static void writePng(Path path, ByteBuffer pixels, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int r = pixels.get(offset) & 0xff;
                int g = pixels.get(offset + 1) & 0xff;
                int b = pixels.get(offset + 2) & 0xff;
                int a = pixels.get(offset + 3) & 0xff;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        if (!ImageIO.write(image, "PNG", path.toFile())) {
            throw new IOException("No PNG writer available");
        }
    }

    /** Writes a standards-compliant, uncompressed scanline OpenEXR with planar B/G/R float channels. */
    static void writeExr(Path path, ByteBuffer pixels, int width, int height) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        attribute(header, "channels", "chlist", channels());
        attribute(header, "compression", "compression", new byte[] {0});
        attribute(header, "dataWindow", "box2i", box(width, height));
        attribute(header, "displayWindow", "box2i", box(width, height));
        attribute(header, "lineOrder", "lineOrder", new byte[] {0});
        attribute(header, "pixelAspectRatio", "float", floats(1.0f));
        attribute(header, "screenWindowCenter", "v2f", floats(0.0f, 0.0f));
        attribute(header, "screenWindowWidth", "float", floats(1.0f));
        header.write(0);

        int scanlineBytes = Math.multiplyExact(width, 12);
        long firstBlock = 8L + header.size() + (long) height * 8L;
        try (OutputStream out = Files.newOutputStream(path)) {
            writeInt(out, EXR_MAGIC);
            writeInt(out, 2);
            header.writeTo(out);
            for (int y = 0; y < height; y++) {
                writeLong(out, firstBlock + (long) y * (8L + scanlineBytes));
            }
            for (int y = 0; y < height; y++) {
                writeInt(out, y);
                writeInt(out, scanlineBytes);
                writeChannel(out, pixels, width, y, 2);
                writeChannel(out, pixels, width, y, 1);
                writeChannel(out, pixels, width, y, 0);
            }
        }
    }

    private static void writeChannel(OutputStream out, ByteBuffer pixels, int width, int y, int component)
            throws IOException {
        for (int x = 0; x < width; x++) {
            writeInt(out, Float.floatToRawIntBits(pixels.getFloat((y * width + x) * 16 + component * 4)));
        }
    }

    private static byte[] channels() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String name : new String[] {"B", "G", "R"}) {
            cString(out, name);
            writeInt(out, 2); // FLOAT
            out.write(0); // pLinear
            out.write(new byte[3]);
            writeInt(out, 1);
            writeInt(out, 1);
        }
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] box(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, width - 1);
        writeInt(out, height - 1);
        return out.toByteArray();
    }

    private static byte[] floats(float... values) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (float value : values) {
            writeInt(out, Float.floatToRawIntBits(value));
        }
        return out.toByteArray();
    }

    private static void attribute(OutputStream out, String name, String type, byte[] value) throws IOException {
        cString(out, name);
        cString(out, type);
        writeInt(out, value.length);
        out.write(value);
    }

    private static void cString(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeLong(OutputStream out, long value) throws IOException {
        writeInt(out, (int) value);
        writeInt(out, (int) (value >>> 32));
    }

    private static SampleStats sampleStats(ByteBuffer pixels, int width, int height) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        long total = 0;
        int count = Math.multiplyExact(width, height);
        for (int i = 0; i < count; i++) {
            int samples = Math.round(pixels.getFloat(i * 16 + 12));
            min = Math.min(min, samples);
            max = Math.max(max, samples);
            total += samples;
        }
        return new SampleStats(count == 0 ? 0 : min, max, count == 0 ? 0.0 : (double) total / count);
    }

    private static void writeManifest(Path path, Path exr, Path png, int width, int height,
                                      SampleStats stats, ExportMetadata metadata) throws IOException {
        String json = "{\n"
                + "  \"renderer\": \"caustica-offline-progressive-v3\",\n"
                + "  \"width\": " + width + ",\n"
                + "  \"height\": " + height + ",\n"
                + "  \"samplesPerBatch\": " + metadata.samplesPerBatch() + ",\n"
                + "  \"minimumSamples\": " + metadata.minimumSamples() + ",\n"
                + "  \"maximumSamples\": " + metadata.maximumSamples() + ",\n"
                + "  \"maximumBounces\": " + metadata.maximumBounces() + ",\n"
                + "  \"adaptive\": " + metadata.adaptive() + ",\n"
                + "  \"relativeError\": " + metadata.relativeError() + ",\n"
                + "  \"absoluteError\": " + metadata.absoluteError() + ",\n"
                + "  \"actualMinimumSamples\": " + stats.minimum() + ",\n"
                + "  \"actualMaximumSamples\": " + stats.maximum() + ",\n"
                + "  \"actualAverageSamples\": " + stats.average() + ",\n"
                + "  \"convergedPixels\": " + metadata.convergedPixels() + ",\n"
                + "  \"totalPixels\": " + Math.multiplyExact(width, height) + ",\n"
                + "  \"elapsedSeconds\": " + metadata.elapsedSeconds() + ",\n"
                + "  \"settingsSignature\": " + metadata.settingsSignature() + ",\n"
                + "  \"linearExr\": " + jsonPath(exr) + ",\n"
                + "  \"displayPng\": " + jsonPath(png) + "\n"
                + "}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static String jsonPath(Path path) {
        return path == null ? "null" : "\"" + path.getFileName().toString().replace("\"", "\\\"") + "\"";
    }

    public record ExportResult(Path exr, Path png, Path manifest) {
    }

    private record SampleStats(int minimum, int maximum, double average) {
    }

    private record ExportMetadata(int samplesPerBatch, int minimumSamples, int maximumSamples,
                                  int maximumBounces, boolean adaptive, float relativeError,
                                  float absoluteError, int convergedPixels, double elapsedSeconds,
                                  int settingsSignature) {
    }
}
