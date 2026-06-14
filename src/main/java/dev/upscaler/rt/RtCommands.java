package dev.upscaler.rt;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.LongBuffer;
import java.util.function.Consumer;

/**
 * Synchronous one-shot command submission for RT init work (AS builds, the spike trace).
 *
 * <p>Blaze3D's {@code VulkanCommandEncoder.execute()} does NOT submit — it defers the command
 * buffer into the frame's submission builder, flushed at frame end. That's wrong for init work
 * that needs to complete before a CPU read-back. Here we own a transient pool and submit
 * directly to the graphics queue with a fence, waiting for completion. Safe because the tick
 * one-shot runs on the render thread with the device idle between frames.
 */
public final class RtCommands {
    private static long commandPool;
    private static int poolFamily = -1;

    private RtCommands() {
    }

    public static synchronized void submitSync(VulkanDevice device, Consumer<VkCommandBuffer> record) {
        VkDevice vk = device.vkDevice();
        VulkanQueue queue = device.graphicsQueue();
        ensurePool(vk, queue.queueFamilyIndex());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(VK10.vkAllocateCommandBuffers(vk, ai, pCmd), "vkAllocateCommandBuffers");
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), vk);

            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(VK10.vkBeginCommandBuffer(cmd, bi), "vkBeginCommandBuffer");
            record.accept(cmd);
            check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer pFence = stack.mallocLong(1);
            check(VK10.vkCreateFence(vk, fci, null, pFence), "vkCreateFence");
            long fence = pFence.get(0);

            VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
            check(VK10.vkQueueSubmit(queue.vkQueue(), si, fence), "vkQueueSubmit");
            check(VK10.vkWaitForFences(vk, pFence, true, Long.MAX_VALUE), "vkWaitForFences");

            VK10.vkDestroyFence(vk, fence, null);
            VK10.vkFreeCommandBuffers(vk, commandPool, pCmd);
        }
    }

    private static void ensurePool(VkDevice vk, int family) {
        if (commandPool != 0L && poolFamily == family) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(family);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateCommandPool(vk, ci, null, p), "vkCreateCommandPool");
            commandPool = p.get(0);
            poolFamily = family;
        }
    }

    private static void check(int rc, String what) {
        if (rc != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + rc);
        }
    }
}
