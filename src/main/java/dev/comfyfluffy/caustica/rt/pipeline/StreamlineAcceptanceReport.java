package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.streamline.StreamlineAbi;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

/** Compact, behavior-neutral runtime proof for fixed Vulkan DLSSG and DLSSD. */
public final class StreamlineAcceptanceReport {
    public static final int SCHEMA_VERSION = 7;
    private static final long DEVELOPMENT_WRITE_INTERVAL_NANOS = 250_000_000L;
    private static long lastWriteNanos;
    private static String lastStateKey = "";

    private StreamlineAcceptanceReport() {
    }

    public static synchronized void publish() {
        long now = System.nanoTime();
        String stateKey = stateKey();
        if (stateKey.equals(lastStateKey) && lastWriteNanos != 0L) {
            // Production reports are durable state transitions, not a periodic telemetry stream. A final
            // report is still forced during shutdown by publishNow(). Development retains its short cadence.
            if (StreamlineRuntime.productionVariant()
                    || now - lastWriteNanos < DEVELOPMENT_WRITE_INTERVAL_NANOS) {
                return;
            }
        }
        writeReports();
        lastWriteNanos = now;
        lastStateKey = stateKey;
    }

    public static synchronized void publishNow() {
        writeReports();
        lastWriteNanos = System.nanoTime();
        lastStateKey = stateKey();
    }

    private static String stateKey() {
        RtDlssFg fg = RtDlssFg.INSTANCE;
        RtDlssRr rr = RtDlssRr.INSTANCE;
        return fg.hasGeneratedFrames() + ":" + fg.isActive() + ":" + fg.queueFallbackActive()
                + ":" + fg.runtimeStatus() + ":" + rr.fallbackActive() + ":" + rr.lastEvaluateResult();
    }

    private static void writeReports() {
        try {
            Path directory = StreamlineRuntime.diagnosticsDirectory();
            Files.createDirectories(directory);
            NativeTrace trace = NativeTrace.read();
            String fgVerdict = frameGenerationPass(trace) ? "PASS" : "PENDING";
            String rrVerdict = rayReconstructionPass(trace) ? "PASS" : "PENDING";
            writeAtomically(directory.resolve("dlssg-acceptance.json"), report(fgVerdict, rrVerdict, trace, true));
            writeAtomically(directory.resolve("dlssd-acceptance.json"), report(fgVerdict, rrVerdict, trace, false));
        } catch (Throwable throwable) {
            CausticaMod.LOGGER.warn("Could not write Streamline schema-{} acceptance report", SCHEMA_VERSION,
                    throwable);
        }
    }

    private static boolean frameGenerationPass(NativeTrace trace) {
        RtDlssFg fg = RtDlssFg.INSTANCE;
        return fg.hasGeneratedFrames()
                && fg.nativeSubmittedMultiFrameCount() >= 1
                && fg.maxFramesActuallyPresented() >= 2
                && trace != null && trace.setOptionsCalls > 0L;
    }

    private static boolean rayReconstructionPass(NativeTrace trace) {
        RtDlssRr rr = RtDlssRr.INSTANCE;
        return CausticaConfig.Rt.DlssRr.ENABLED.value()
                && rr.lastOptionsResult() == 0
                && rr.lastEvaluateResult() == 0
                && rr.javaOptionsCalls() > 0L
                && rr.javaEvaluateCalls() > 0L
                && rr.lastResourceCount() == RtDlssRr.requiredResourceCount()
                && !rr.fallbackActive()
                && trace != null
                && trace.dlssdOptionsCalls > 0L
                && trace.dlssdEvaluateCalls > 0L
                && trace.lastDlssdResourceCount == RtDlssRr.requiredResourceCount()
                && trace.lastDlssdResult == 0
                && trace.lastDlssdViewport == 1;
    }

    private static String report(String fgVerdict, String rrVerdict, NativeTrace trace, boolean fgPrimary) {
        RtDlssFg fg = RtDlssFg.INSTANCE;
        RtDlssRr rr = RtDlssRr.INSTANCE;
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n")
                .append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
                .append("  \"capturedAt\": ").append(quote(Instant.now().toString())).append(",\n")
                .append("  \"processId\": ").append(ProcessHandle.current().pid()).append(",\n")
                .append("  \"identity\": {\n")
                .append("    \"streamlineVariant\": ").append(quote(StreamlineRuntime.variant())).append(",\n")
                .append("    \"production\": ").append(StreamlineRuntime.productionVariant()).append(",\n")
                .append("    \"developmentBehaviorOverrideActive\": ")
                .append(StreamlineRuntime.behaviorOverrideActive()).append("\n")
                .append("  },\n")
                .append("  \"primaryFeature\": ").append(quote(fgPrimary ? "DLSSG" : "DLSSD")).append(",\n")
                .append("  \"verdict\": ").append(quote(fgPrimary ? fgVerdict : rrVerdict)).append(",\n");

        appendOverrides(json, CausticaConfig.activeOverrides());
        json.append(",\n  \"dlssg\": {\n")
                .append("    \"verdict\": ").append(quote(fgVerdict)).append(",\n")
                .append("    \"configuredGeneratedFrames\": ").append(fg.configuredMultiFrameCount()).append(",\n")
                .append("    \"effectiveGeneratedFrames\": ").append(fg.effectiveMultiFrameCount()).append(",\n")
                .append("    \"nativeSubmittedGeneratedFrames\": ")
                .append(fg.nativeSubmittedMultiFrameCount()).append(",\n")
                .append("    \"nativeSubmittedMode\": ").append(fg.nativeSubmittedMode()).append(",\n")
                .append("    \"reportedMaximumGeneratedFrames\": ").append(fg.multiFrameCountMax()).append(",\n")
                .append("    \"lastNumFramesActuallyPresented\": ").append(fg.framesActuallyPresented()).append(",\n")
                .append("    \"maximumNumFramesActuallyPresented\": ")
                .append(fg.maxFramesActuallyPresented()).append(",\n")
                .append("    \"capabilityStateValid\": ").append(fg.capabilityStateValid()).append(",\n")
                .append("    \"currentSwapchainStateKnown\": ").append(fg.currentStateKnown()).append(",\n")
                .append("    \"dynamicMfgSupported\": ").append(fg.dynamicMfgSupported()).append(",\n")
                .append("    \"dynamicMfgLimitation\": ").append(quote(RtDlssFg.dynamicMfgLimitation())).append(",\n")
                .append("    \"generatedFramesConfirmed\": ").append(fg.hasGeneratedFrames()).append(",\n")
                .append("    \"logicalVsyncRequested\": ").append(fg.logicalVsyncRequested()).append(",\n")
                .append("    \"physicalFifoPresent\": ").append(fg.physicalFifoPresent()).append(",\n")
                .append("    \"vsyncRequested\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.vsyncRequested()).append(",\n")
                .append("    \"mailboxSupported\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.mailboxSupported()).append(",\n")
                .append("    \"mailboxVsyncCompatibility\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.mailboxVsyncCompatibility()).append(",\n")
                .append("    \"presentMode\": ")
                .append(quote(StreamlineSwapchainCoordinator.INSTANCE.presentMode())).append(",\n")
                .append("    \"reflexIntervalUs\": ").append(fg.reflexIntervalUs()).append(",\n")
                .append("    \"successfulOptionSubmissions\": ").append(fg.successfulOptionsSubmissions()).append(",\n")
                .append("    \"requestedQueueMode\": ").append(quote(fg.requestedQueueMode())).append(",\n")
                .append("    \"effectiveQueueMode\": ").append(quote(fg.effectiveQueueMode())).append(",\n")
                .append("    \"queuePolicyReason\": ").append(quote(fg.queuePolicyReason())).append(",\n")
                .append("    \"queueFallbackActive\": ").append(fg.queueFallbackActive()).append(",\n")
                .append("    \"queueFallbackReason\": ").append(quote(fg.queueFallbackReason())).append(",\n")
                .append("    \"inputSlotCount\": ").append(fg.inputSlotCount()).append(",\n")
                .append("    \"activeInputSlot\": ").append(fg.activeInputSlot()).append(",\n")
                .append("    \"acquiredApplicationImage\": ").append(fg.acquiredApplicationImage()).append(",\n")
                .append("    \"inputSlotRetirements\": ").append(quote(fg.inputSlotRetirements())).append(",\n")
                .append("    \"lastInputsProcessingFence\": ").append(quote(hex(fg.lastInputFence()))).append(",\n")
                .append("    \"lastInputsProcessingFenceValue\": ").append(fg.lastInputFenceValue()).append(",\n")
                .append("    \"timelineWaitCount\": ").append(fg.timelineWaitCount()).append(",\n")
                .append("    \"timelineWaitTotalNanos\": ").append(fg.timelineWaitTotalNanos()).append(",\n")
                .append("    \"timelineWaitMaximumNanos\": ").append(fg.timelineWaitMaximumNanos()).append(",\n")
                .append("    \"timelineWaitFailures\": ").append(fg.timelineWaitFailures()).append(",\n")
                .append("    \"controlledDeviceIdleCount\": ")
                .append(StreamlineRuntime.controlledDeviceIdleCount()).append(",\n")
                .append("    \"steadyStateDeviceIdleCount\": ")
                .append(StreamlineRuntime.steadyStateDeviceIdleCount()).append(",\n")
                .append("    \"deviceIdleReasons\": ")
                .append(quote(StreamlineRuntime.deviceIdleReasons())).append(",\n")
                .append("    \"nativeTimelineWaitCalls\": ").append(trace == null ? 0L : trace.timelineWaitCalls).append(",\n")
                .append("    \"nativeTimelineWaitFailures\": ").append(trace == null ? 0L : trace.timelineWaitFailures).append(",\n")
                .append("    \"nativeDeviceWaitIdleCalls\": ").append(trace == null ? 0L : trace.deviceWaitIdleCalls).append(",\n")
                .append("    \"nativeLastTimelineSemaphore\": ")
                .append(quote(hex(trace == null ? 0L : trace.lastTimelineSemaphore))).append(",\n")
                .append("    \"nativeLastTimelineValue\": ").append(trace == null ? 0L : trace.lastTimelineValue).append(",\n")
                .append("    \"nativeSetOptionsCalls\": ").append(trace == null ? 0L : trace.setOptionsCalls).append(",\n")
                .append("    \"nativeLastOptionsMode\": ").append(trace == null ? -1 : trace.lastOptionsMode).append(",\n")
                .append("    \"nativeLastGeneratedFrames\": ").append(trace == null ? -1 : trace.lastNumFrames).append(",\n")
                .append("    \"nativeLastStateStatus\": ").append(trace == null ? -1 : trace.lastDlssgStatus).append(",\n")
                .append("    \"nativeLastFramesPresented\": ").append(trace == null ? -1 : trace.lastFramesPresented).append(",\n")
                .append("    \"flipMeteringState\": ")
                .append(quote(StreamlineRuntime.flipMeteringState())).append(",\n")
                .append("    \"streamlineVsyncSupportAvailable\": ").append(fg.vsyncSupportAvailable()).append(",\n")
                .append("    \"requestedPresentMode\": ")
                .append(quote(StreamlineSwapchainCoordinator.INSTANCE.requestedPresentMode())).append(",\n")
                .append("    \"normalizedPresentMode\": ")
                .append(quote(StreamlineSwapchainCoordinator.INSTANCE.normalizedPresentMode())).append(",\n")
                .append("    \"nativePresentMode\": ")
                .append(quote(StreamlineSwapchainCoordinator.INSTANCE.nativePresentMode())).append(",\n")
                .append("    \"nativePresentModeValue\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.nativePresentModeValue()).append(",\n")
                .append("    \"nativePresentModeKnown\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.nativePresentModeKnown()).append(",\n")
                .append("    \"requestedNativeMinImageCount\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.requestedNativeMinImageCount()).append(",\n")
                .append("    \"applicationImageCount\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.applicationImageCount()).append(",\n")
                .append("    \"proxyVisibleImageCount\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.proxyVisibleImageCount()).append(",\n")
                .append("    \"nativeCreateResult\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.nativeCreateResult()).append(",\n")
                .append("    \"nativeProxyDispatch\": ")
                .append(StreamlineSwapchainCoordinator.INSTANCE.nativeProxyDispatch()).append(",\n")
                .append("    \"nativeSwapchain\": ")
                .append(quote(StreamlineSwapchainCoordinator.INSTANCE.nativeSwapchainHandle())).append("\n")
                .append("  },\n")
                .append("  \"dlssd\": {\n")
                .append("    \"verdict\": ").append(quote(rrVerdict)).append(",\n")
                .append("    \"configured\": ").append(CausticaConfig.Rt.DlssRr.ENABLED.configuredValue()).append(",\n")
                .append("    \"effective\": ").append(CausticaConfig.Rt.DlssRr.ENABLED.value()).append(",\n")
                .append("    \"overridden\": ").append(CausticaConfig.Rt.DlssRr.ENABLED.isOverridden()).append(",\n")
                .append("    \"overrideSource\": ")
                .append(quote(CausticaConfig.Rt.DlssRr.ENABLED.overrideSource())).append(",\n")
                .append("    \"lastOptionsResult\": ").append(rr.lastOptionsResult()).append(",\n")
                .append("    \"lastEvaluationResult\": ").append(rr.lastEvaluateResult()).append(",\n")
                .append("    \"javaOptionsCalls\": ").append(rr.javaOptionsCalls()).append(",\n")
                .append("    \"javaEvaluationCalls\": ").append(rr.javaEvaluateCalls()).append(",\n")
                .append("    \"requiredResourceCount\": ").append(RtDlssRr.requiredResourceCount()).append(",\n")
                .append("    \"submittedResourceCount\": ").append(rr.lastResourceCount()).append(",\n")
                .append("    \"fallbackActive\": ").append(rr.fallbackActive()).append(",\n")
                .append("    \"fallbackFrames\": ").append(rr.fallbackFrames()).append(",\n")
                .append("    \"fallbackReason\": ").append(quote(rr.fallbackReason())).append(",\n")
                .append("    \"nativeOptionsCalls\": ").append(trace == null ? 0L : trace.dlssdOptionsCalls).append(",\n")
                .append("    \"nativeEvaluationCalls\": ").append(trace == null ? 0L : trace.dlssdEvaluateCalls).append(",\n")
                .append("    \"nativeFrameToken\": ").append(quote(hex(trace == null ? 0L : trace.lastDlssdFrameToken))).append(",\n")
                .append("    \"nativeCommandBuffer\": ").append(quote(hex(trace == null ? 0L : trace.lastDlssdCommandBuffer))).append(",\n")
                .append("    \"nativeMode\": ").append(trace == null ? -1 : trace.lastDlssdMode).append(",\n")
                .append("    \"nativeResourceCount\": ").append(trace == null ? -1 : trace.lastDlssdResourceCount).append(",\n")
                .append("    \"nativeResult\": ").append(trace == null ? -1 : trace.lastDlssdResult).append(",\n")
                .append("    \"nativeViewport\": ").append(trace == null ? -1 : trace.lastDlssdViewport).append("\n")
                .append("  }\n")
                .append("}\n");
        return json.toString();
    }

    private static void appendOverrides(StringBuilder json, List<CausticaConfig.RuntimeSetting<?>> overrides) {
        json.append("  \"activeOverrides\": [");
        for (int index = 0; index < overrides.size(); index++) {
            CausticaConfig.RuntimeSetting<?> setting = overrides.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("\n    {\"key\": ").append(quote(setting.key()))
                    .append(", \"source\": ").append(quote(setting.overrideSource()))
                    .append(", \"raw\": ").append(quote(setting.overrideRawValue()))
                    .append(", \"configured\": ").append(quote(String.valueOf(setting.configuredValue())))
                    .append(", \"effective\": ").append(quote(String.valueOf(setting.get())))
                    .append('}');
        }
        if (!overrides.isEmpty()) {
            json.append('\n').append("  ");
        }
        json.append(']');
    }

    private static void writeAtomically(Path target, String contents) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, contents);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + '"';
    }

    private static String hex(long value) {
        return "0x" + Long.toUnsignedString(value, 16);
    }

    private record NativeTrace(long setOptionsCalls, int lastOptionsMode, int lastNumFrames,
            int lastDlssgStatus, int lastFramesPresented, long dlssdOptionsCalls,
            long dlssdEvaluateCalls, long lastDlssdFrameToken, long lastDlssdCommandBuffer,
            int lastDlssdMode, int lastDlssdResourceCount, int lastDlssdResult,
            int lastDlssdViewport, long timelineWaitCalls, long timelineWaitFailures,
            long deviceWaitIdleCalls, long lastTimelineSemaphore, long lastTimelineValue) {
        private static NativeTrace read() {
            if (!StreamlineRuntime.initialized() || StreamlineRuntime.library() == null) {
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = StreamlineAbi.allocate(arena, StreamlineAbi.TRACE_STATE_SIZE);
                if (StreamlineRuntime.library().getTraceState(state) != 0) {
                    return null;
                }
                ByteBuffer bytes = StreamlineAbi.bytes(state);
                return new NativeTrace(bytes.getLong(24), bytes.getInt(168), bytes.getInt(172),
                        bytes.getInt(216), bytes.getInt(220), bytes.getLong(248), bytes.getLong(256),
                        bytes.getLong(272), bytes.getLong(280), bytes.getInt(288), bytes.getInt(292),
                        bytes.getInt(296), bytes.getInt(300),
                        bytes.getLong(StreamlineAbi.TRACE_TIMELINE_WAIT_CALLS_OFFSET),
                        bytes.getLong(StreamlineAbi.TRACE_TIMELINE_WAIT_FAILURES_OFFSET),
                        bytes.getLong(StreamlineAbi.TRACE_DEVICE_WAIT_IDLE_CALLS_OFFSET),
                        bytes.getLong(StreamlineAbi.TRACE_LAST_TIMELINE_SEMAPHORE_OFFSET),
                        bytes.getLong(StreamlineAbi.TRACE_LAST_TIMELINE_VALUE_OFFSET));
            } catch (Throwable throwable) {
                return null;
            }
        }
    }
}
