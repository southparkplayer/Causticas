package dev.upscaler.rt;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * P5.1b-2b: resolves the texture backing an entity {@link RenderType} to a Vulkan image-view handle,
 * for the bindless entity-texture array. Entities use per-type texture files (zombie.png, …), not the
 * block atlas, so each distinct texture maps to its own bindless slot. Slots are keyed by the resolved
 * image view rather than the {@link RenderType}: some render types are rebuilt every frame with a fresh
 * identity (the charged-creeper energy swirl scrolls its texture transform, so {@code energySwirl()}
 * allocates a new RenderType per frame), and keying by RenderType there would leak a slot per frame
 * until the array exhausts and everything falls back to slot 0.
 *
 * <p>The view is obtained through the <b>public</b> {@code RenderType.prepare()} → {@link
 * PreparedRenderType#textures()} API (a list of {@code Texture(name, GpuTextureView, sampler)}): the
 * primary sampler is {@code "Sampler0"} ({@code "Sampler1"}/{@code "Sampler2"} are the overlay/lightmap
 * the prepared list prepends). Resolution is cached per {@code RenderType} (they are stable singletons),
 * so the prepare() cost is paid once per distinct texture.
 *
 * <p>Step b1 here is resolution only (verified by the entity probe); the bindless descriptor array +
 * slot assignment + shader sampling are step b2.
 */
public final class RtEntityTextures {
    public static final RtEntityTextures INSTANCE = new RtEntityTextures();
    /** Bindless array capacity (slot 0 reserved as a fallback texture). {@code -Dupscaler.rt.maxEntityTextures}. */
    public static final int MAX_TEXTURES = Integer.getInteger("upscaler.rt.maxEntityTextures", 256);

    // RenderType identity → resolved primary image-view handle. WEAK: some render types are rebuilt
    // every frame with a fresh identity (e.g. the charged-creeper energy-swirl layer, whose scrolling
    // texture transform makes RenderTypes.energySwirl() allocate a new RenderType each frame). A weak map
    // lets those dead identities be collected instead of accumulating; stable singletons (zombie.png, …)
    // stay cached and skip the costly RenderType.prepare().
    private final Map<RenderType, Long> viewCache = new WeakHashMap<>();
    // Resolved image-view handle → bindless slot. The slot identifies a *texture*, not a RenderType, so
    // many render types that differ only by a texture transform we don't replicate (the swirl's scroll)
    // collapse to ONE slot — instead of leaking a slot per frame until the array exhausts and everything
    // falls back to slot 0 (the block atlas). Append-only: a handle's slot never changes once assigned,
    // so update-after-bind writes for new slots never disturb in-flight frames.
    private final Map<Long, Integer> viewSlotCache = new HashMap<>();
    // Atlas-location → bindless slot, for items/blocks (which texture from an atlas, not a per-type
    // file). Seeded with the block atlas = slot 0 (also the fallback). Items use a separate item atlas.
    private final Map<Identifier, Integer> atlasSlotCache = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>(); // slots resolved this frame, awaiting upload
    private int nextSlot = 1;
    private boolean loggedFailure;

    private record Pending(int slot, long view) {
    }

    private RtEntityTextures() {
    }

    /**
     * The bindless slot for {@code renderType}'s texture. Keyed by the resolved image view, not the
     * RenderType, so per-frame-allocated render types sharing one texture reuse a single slot. Returns 0
     * (the fallback slot = block atlas) if the texture can't be resolved or the array is full.
     */
    public int slotFor(RenderType renderType) {
        if (renderType == null) {
            return 0;
        }
        return slotForView(resolveView(renderType));
    }

    /**
     * The bindless slot for a texture atlas (block/item atlas used by item + block-model quads), cached
     * per atlas location. The block atlas is pre-seeded to slot 0; other atlases (the item atlas) get
     * their own slot. Returns 0 (fallback) if unresolvable or the array is full.
     */
    public int slotForAtlas(Identifier atlasLocation) {
        if (atlasLocation == null) {
            return 0;
        }
        Integer cached = atlasSlotCache.get(atlasLocation);
        if (cached != null) {
            return cached;
        }
        long view = 0L;
        try {
            GpuTextureView v = Minecraft.getInstance().getTextureManager().getTexture(atlasLocation).getTextureView();
            view = vkImageView(v);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                UpscalerMod.LOGGER.warn("RT atlas texture resolution failed for {}", atlasLocation, t);
            }
        }
        int slot = slotForView(view);
        atlasSlotCache.put(atlasLocation, slot);
        return slot;
    }

    /**
     * Map a resolved image-view handle to a stable bindless slot, allocating one on first sight (queued
     * for upload via {@link #uploadPending}). Returns 0 (fallback) when the view is unresolved or the
     * array is full. Deduping by handle is what bounds slot use: distinct render types backed by the same
     * texture share a slot instead of each consuming one.
     */
    private int slotForView(long view) {
        if (view == 0L) {
            return 0;
        }
        Integer cached = viewSlotCache.get(view);
        if (cached != null) {
            return cached;
        }
        if (nextSlot >= MAX_TEXTURES) {
            return 0;
        }
        int slot = nextSlot++;
        viewSlotCache.put(view, slot);
        pending.add(new Pending(slot, view));
        return slot;
    }

    /** Write any newly-registered entity textures into the pipeline's bindless set (before the trace). */
    public void uploadPending(RtPipeline pipeline, long sampler) {
        if (pending.isEmpty()) {
            return;
        }
        for (Pending p : pending) {
            pipeline.setBindlessTexture(p.slot(), p.view(), sampler);
        }
        pending.clear();
    }

    /** Drop the registry (call when the world pipeline / bindless set is recreated, or textures reload). */
    public void reset() {
        viewCache.clear();
        viewSlotCache.clear();
        atlasSlotCache.clear();
        atlasSlotCache.put(TextureAtlas.LOCATION_BLOCKS, 0); // block atlas = the slot-0 fallback
        pending.clear();
        nextSlot = 1;
    }

    /** The Vulkan image-view handle of {@code renderType}'s primary texture, or 0 if it can't be resolved. */
    public long resolveView(RenderType renderType) {
        if (renderType == null) {
            return 0L;
        }
        Long cached = viewCache.get(renderType);
        if (cached != null) {
            return cached;
        }
        long handle = 0L;
        try {
            PreparedRenderType prepared = renderType.prepare();
            GpuTextureView chosen = null;
            GpuTextureView firstNonAux = null;
            for (PreparedRenderType.Texture t : prepared.textures()) {
                String name = t.name();
                if ("Sampler0".equals(name)) {
                    chosen = t.textureView();
                    break;
                }
                if (firstNonAux == null && !"Sampler1".equals(name) && !"Sampler2".equals(name)) {
                    firstNonAux = t.textureView();
                }
            }
            if (chosen == null) {
                chosen = firstNonAux;
            }
            if (chosen != null) {
                handle = vkImageView(chosen);
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                UpscalerMod.LOGGER.warn("RT entity texture resolution failed for {}", renderType, t);
            }
        }
        viewCache.put(renderType, handle);
        return handle;
    }

    private static long vkImageView(GpuTextureView view) {
        Long sodiumHandle = SodiumCompat.vkImageView(view);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
