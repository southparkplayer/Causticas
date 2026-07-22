import world_common;

static const uint OFFLINE_MIN_ADAPTIVE_SAMPLES = 64u;

struct OfflineScheduleState {
    uint indirectWidth;
    uint indirectHeight;
    uint indirectDepth;
    uint activeCount;
    uint mainPathCountLo;
    uint mainPathCountHi;
    uint pilotPathCountLo;
    uint pilotPathCountHi;
    uint overflowCount;
    uint totalTiles;
};

struct OfflineTileWork {
    uint tileIndex;
    uint pathCount;
};

typealias OfflineRWPtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device>;

struct OfflineSchedulePush {
    uint64_t workAddress;
    uint64_t stateAddress;
    uint tileWidth;
    uint tileHeight;
    uint imageWidth;
    uint imageHeight;
    uint maximumTiles;
    uint frameIndex;
    uint configuredBatchLimit;
    float targetRelativeError;
};

[[vk::push_constant]] OfflineSchedulePush sp;

[[vk::binding(0, 0)]] [format("rgba32f")] RWTexture2D<float4> offlinePilotState;
[[vk::binding(1, 0)]] [format("r32ui")] RWTexture2D<uint> offlineSampleCount;

inline uint scheduleHash(uint value) {
    value ^= value >> 16u;
    value *= 0x21f0aaadu;
    value ^= value >> 15u;
    value *= 0xf35a2d97u;
    value ^= value >> 15u;
    return value;
}

inline bool tileActiveThisFrame(uint2 tile, uint frameIndex, uint cadence) {
    uint hash = scheduleHash(tile.x ^ scheduleHash(tile.y + 0x9e3779b9u));
    uint phase = hash & (cadence - 1u);
    return ((frameIndex + phase) & (cadence - 1u)) == 0u;
}

inline uint offlineCadence(float relativeVariance, uint sampleCount, float targetRelativeError) {
    if (isnan(relativeVariance) || isinf(relativeVariance) || relativeVariance < 0.0
            || isnan(targetRelativeError) || isinf(targetRelativeError) || targetRelativeError <= 0.0
            || sampleCount < OFFLINE_MIN_ADAPTIVE_SAMPLES) {
        return 1u;
    }
    float predictedError = sqrt(relativeVariance) / sqrt(max(1.0, float(sampleCount)));
    uint cadence = 1u;
    while (cadence < 32u && predictedError * float(cadence) < targetRelativeError) {
        cadence <<= 1u;
    }
    return cadence;
}

inline uint offlinePathCount(float relativeVariance, uint sampleCount, uint batchLimit,
        float targetRelativeError) {
    if (isnan(relativeVariance) || isinf(relativeVariance) || relativeVariance < 0.0
            || isnan(targetRelativeError) || isinf(targetRelativeError) || targetRelativeError <= 0.0
            || sampleCount < OFFLINE_MIN_ADAPTIVE_SAMPLES) {
        return batchLimit;
    }
    float predictedError = sqrt(relativeVariance) / sqrt(max(1.0, float(sampleCount)));
    return min(batchLimit, max(1u, uint(predictedError * 100.0 + 0.999)));
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThread : SV_DispatchThreadID) {
    uint totalTiles = sp.tileWidth * sp.tileHeight;
    uint tileIndex = dispatchThread.x;
    if (tileIndex >= totalTiles) {
        return;
    }

    uint2 tile = uint2(tileIndex % sp.tileWidth, tileIndex / sp.tileWidth);
    uint2 firstPixel = tile << 3u;
    uint validWidth = min(8u, sp.imageWidth - firstPixel.x);
    uint validHeight = min(8u, sp.imageHeight - firstPixel.y);
    uint validPixels = validWidth * validHeight;
    float4 packedPilot = offlinePilotState[int2(tile)];
    float relativeVariance = max(packedPilot.r, max(packedPilot.g, packedPilot.b));
    uint completedSamples = 0xffffffffu;
    for (uint y = 0u; y < validHeight; y++) {
        for (uint x = 0u; x < validWidth; x++) {
            completedSamples = min(completedSamples,
                    offlineSampleCount[int2(firstPixel + uint2(x, y))]);
        }
    }
    uint cadence = offlineCadence(relativeVariance, completedSamples, sp.targetRelativeError);
    if (!tileActiveThisFrame(tile, sp.frameIndex, cadence)) {
        return;
    }

    uint pathCount = offlinePathCount(relativeVariance, completedSamples,
            sp.configuredBatchLimit, sp.targetRelativeError);
    OfflineRWPtr<OfflineScheduleState> state = OfflineRWPtr<OfflineScheduleState>(sp.stateAddress);
    OfflineRWPtr<OfflineTileWork> work = OfflineRWPtr<OfflineTileWork>(sp.workAddress);
    uint slot;
    InterlockedAdd(state[0].activeCount, 1u, slot);
    if (slot >= sp.maximumTiles) {
        InterlockedAdd(state[0].overflowCount, 1u);
        return;
    }
    work[slot].tileIndex = tileIndex;
    work[slot].pathCount = pathCount;
    InterlockedAdd(state[0].indirectWidth, 64u);
    InterlockedAdd(state[0].mainPathCountLo, validPixels * pathCount);
    state[0].totalTiles = totalTiles;
}
