package dev.upscaler.rt.pipeline;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDebugLabels;
import dev.upscaler.rt.accel.RtBuffer;

import static dev.upscaler.rt.RtContext.check;

/**
 * Entity glow (Glowing-effect) outline — full-res, post-upscale, depth-less, same seam {@code RtUiOverlay}
 * composites the GUI at. Two raster passes, both via {@code VK_KHR_dynamic_rendering} (already required +
 * enabled by vanilla's own Blaze3D device bring-up — see {@code VulkanBackend.REQUIRED_DEVICE_FEATURES} —
 * so no render pass/framebuffer object is needed for a one-off attachment):
 * <ol>
 *   <li>{@code recordMask} re-rasterizes this frame's glowing entities (their CPU-side capture positions,
 *   already kept around by {@code RtEntities} for BLAS refit) with a trivial unlit pipeline into a full-res
 *   RGBA8 mask (rgb = the entity's vanilla outline colour, a = coverage) — a mod-owned storage image, safe
 *   to read/write however needed.</li>
 *   <li>{@code recordComposite} Sobel-edges that mask and blends the ~2px boundary onto the main render
 *   target's colour attachment via fixed-function blending — <b>not</b> a compute {@code imageStore}: a
 *   vanilla Blaze3D texture is never created with {@code VK_IMAGE_USAGE_STORAGE_BIT} (confirmed by
 *   validation: {@code VUID-VkWriteDescriptorSet-descriptorType-00339}), so writing into it is only valid
 *   as a render-pass/dynamic-rendering colour attachment — exactly how {@code RtUiOverlay} composites the
 *   GUI back onto the same target.</li>
 * </ol>
 * Matches vanilla's silhouette-through-walls look without ever touching a depth buffer. Never makes the
 * entity itself emissive.
 */
public final class RtGlowOutline {
    private static final String SHADER_DIR = "/upscaler/rt/";
    // mat4 curViewProj (0, 64B) + vec3 camOffset (64, padded to 16B) + vec4 color (80, 16B) = 96B.
    private static final int RASTER_PUSH_BYTES = 96;
    private static final int MASK_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    // The main render target's format — matches RtUiOverlay's own GUI-composite pipeline (GpuFormat.RGBA8_UNORM).
    private static final int MAIN_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private final RtContext ctx;
    private final long rasterPipelineLayout;
    private final long rasterPipeline;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long compositePipelineLayout;
    private final long compositePipeline;
    private long boundMaskView;
    private boolean destroyed;

    private RtGlowOutline(RtContext ctx, long rasterPipelineLayout, long rasterPipeline, long dsl, long pool,
                          long set, long compositePipelineLayout, long compositePipeline) {
        this.ctx = ctx;
        this.rasterPipelineLayout = rasterPipelineLayout;
        this.rasterPipeline = rasterPipeline;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.compositePipelineLayout = compositePipelineLayout;
        this.compositePipeline = compositePipeline;
    }

    public static RtGlowOutline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            // --- Raster pipeline: unlit flat-colour mask, no depth attachment, dynamic viewport/scissor. ---
            VkPushConstantRange.Buffer rasterPush = VkPushConstantRange.calloc(1, stack);
            rasterPush.get(0).stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0).size(RASTER_PUSH_BYTES);
            VkPipelineLayoutCreateInfo rasterLayoutCi = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pPushConstantRanges(rasterPush);
            check(VK10.vkCreatePipelineLayout(vk, rasterLayoutCi, null, p), "vkCreatePipelineLayout(glow raster)");
            long rasterLayout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, rasterLayout, "glow raster pipeline layout");

            long vertModule = loadModule(vk, stack, "entity_glow.vert.spv");
            long fragModule = loadModule(vk, stack, "entity_glow.frag.spv");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
            binding.get(0).binding(0).stride(3 * Float.BYTES).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(1, stack);
            attr.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                    .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attr);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL).cullMode(VK10.VK_CULL_MODE_NONE)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttach.get(0).blendEnable(false).colorWriteMask(
                    VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blendAttach);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(MASK_FORMAT));

            VkGraphicsPipelineCreateInfo.Buffer gpci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            gpci.get(0).sType$Default().pNext(renderingInfo.address())
                    .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState).pRasterizationState(raster).pMultisampleState(multisample)
                    .pColorBlendState(colorBlend).pDynamicState(dynamicState).layout(rasterLayout)
                    .renderPass(VK10.VK_NULL_HANDLE).subpass(0);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateGraphicsPipelines(vk, VK10.VK_NULL_HANDLE, gpci, null, pPipeline),
                    "vkCreateGraphicsPipelines(glow raster)");
            long rasterPipeline = pPipeline.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, rasterPipeline, "glow raster pipeline");
            VK10.vkDestroyShaderModule(vk, vertModule, null);
            VK10.vkDestroyShaderModule(vk, fragModule, null);

            // --- Composite pipeline: fullscreen-triangle fragment pass, Sobel-edges the mask and blends the
            // outline onto the main target's colour ATTACHMENT (fixed-function blend) — no vertex buffer
            // (gl_VertexIndex trick), no push constants, no depth.
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(glow composite)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "glow composite descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(glow composite)");
            long descPool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, descPool, "glow composite descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descPool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(glow composite)");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "glow composite descriptor set");

            VkPipelineLayoutCreateInfo compositeLayoutCi = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl));
            check(VK10.vkCreatePipelineLayout(vk, compositeLayoutCi, null, p), "vkCreatePipelineLayout(glow composite)");
            long compositeLayout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, compositeLayout, "glow composite pipeline layout");

            long compVertModule = loadModule(vk, stack, "entity_glow_composite.vert.spv");
            long compFragModule = loadModule(vk, stack, "entity_glow_composite.frag.spv");
            VkPipelineShaderStageCreateInfo.Buffer compositeStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            compositeStages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(compVertModule).pName(stack.UTF8("main"));
            compositeStages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(compFragModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo emptyVertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();

            VkPipelineColorBlendAttachmentState.Buffer compositeBlendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            compositeBlendAttach.get(0).blendEnable(true)
                    .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                    .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                    .alphaBlendOp(VK10.VK_BLEND_OP_ADD)
                    .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo compositeColorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(compositeBlendAttach);

            VkPipelineRenderingCreateInfo compositeRenderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(MAIN_FORMAT));

            VkGraphicsPipelineCreateInfo.Buffer compositeGpci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            compositeGpci.get(0).sType$Default().pNext(compositeRenderingInfo.address())
                    .pStages(compositeStages).pVertexInputState(emptyVertexInput).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState).pRasterizationState(raster).pMultisampleState(multisample)
                    .pColorBlendState(compositeColorBlend).pDynamicState(dynamicState).layout(compositeLayout)
                    .renderPass(VK10.VK_NULL_HANDLE).subpass(0);
            LongBuffer pCompositePipeline = stack.mallocLong(1);
            check(VK10.vkCreateGraphicsPipelines(vk, VK10.VK_NULL_HANDLE, compositeGpci, null, pCompositePipeline),
                    "vkCreateGraphicsPipelines(glow composite)");
            long compositePipeline = pCompositePipeline.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, compositePipeline, "glow composite pipeline");
            VK10.vkDestroyShaderModule(vk, compVertModule, null);
            VK10.vkDestroyShaderModule(vk, compFragModule, null);

            return new RtGlowOutline(ctx, rasterLayout, rasterPipeline, dsl, descPool, set, compositeLayout, compositePipeline);
        }
    }

    /** Bind the mask (read by the composite pass) — the raster pass takes it directly as its attachment. */
    public void setMaskImage(long maskView) {
        if (boundMaskView == maskView) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer maskInfo = VkDescriptorImageInfo.calloc(1, stack);
            maskInfo.get(0).imageView(maskView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(maskInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundMaskView = maskView;
    }

    /**
     * Re-rasterize this frame's glowing entities into {@code maskView} (cleared to transparent black first).
     * {@code vbo}/{@code ibo} hold every entity's positions/indices concatenated (indices already offset to
     * the combined vertex buffer); {@code firstIndex[i]}/{@code indexCount[i]}/{@code colorRgba[4*i..+4]}
     * describe draw {@code i}. {@code camOffset} + {@code curViewProj} mirror the exact camera transform
     * {@code world.rgen} used this frame (see {@code RtEntities.glowCamOffsetX/Y/Z}), so the mask lands
     * pixel-exact on the ray-traced entities.
     */
    public void recordMask(VkCommandBuffer cmd, long maskView, int width, int height, Matrix4f curViewProj,
                           float camOffX, float camOffY, float camOffZ, RtBuffer vbo, RtBuffer ibo,
                           int[] firstIndex, int[] indexCount, float[] colorRgba, int drawCount) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "glow entity mask")) {
            VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
            clear.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f));
            VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                    .imageView(maskView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(clear.get(0));
            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
            renderArea.extent().set(width, height);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                    .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, rasterPipeline);
            setViewportAndScissor(cmd, stack, width, height);
            VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vbo.handle), stack.longs(0L));
            VK10.vkCmdBindIndexBuffer(cmd, ibo.handle, 0, VK10.VK_INDEX_TYPE_UINT32);

            ByteBuffer push = stack.malloc(RASTER_PUSH_BYTES);
            curViewProj.get(0, push);
            for (int i = 0; i < drawCount; i++) {
                push.putFloat(64, camOffX).putFloat(68, camOffY).putFloat(72, camOffZ);
                push.putFloat(80, colorRgba[i * 4]).putFloat(84, colorRgba[i * 4 + 1])
                        .putFloat(88, colorRgba[i * 4 + 2]).putFloat(92, colorRgba[i * 4 + 3]);
                VK10.vkCmdPushConstants(cmd, rasterPipelineLayout,
                        VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
                VK10.vkCmdDrawIndexed(cmd, indexCount[i], 1, firstIndex[i], 0, 0);
            }
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        }
    }

    /**
     * Sobel-edge the mask and blend the outline onto {@code mainColorView} (the main render target's colour
     * attachment) via fixed-function blending — {@code loadOp = LOAD} (this composites onto the existing
     * world content, never clears it). No vertex/index buffers (fullscreen triangle via {@code
     * gl_VertexIndex}).
     */
    public void recordComposite(VkCommandBuffer cmd, long mainColorView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "glow entity composite")) {
            VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                    .imageView(mainColorView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
            renderArea.extent().set(width, height);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                    .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline);
            setViewportAndScissor(cmd, stack, width, height);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        }
    }

    private static void setViewportAndScissor(VkCommandBuffer cmd, MemoryStack stack, int width, int height) {
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, compositePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, compositePipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        VK10.vkDestroyPipeline(vk, rasterPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, rasterPipelineLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtGlowOutline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
