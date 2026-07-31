package ru.pewars.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.util.Lazy;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.lwjgl.glfw.GLFW;

/**
 * Главный клиентский класс мода pe_wars.
 * Регистрирует клавишу H (открыть меню войн) и обрабатывает чат-маркеры
 * [PETM_WAR]/[PETM_RAID] от серверного плагина.
 */
@Mod(PeWarsClient.MOD_ID)
public class PeWarsClient {
    public static final String MOD_ID = "pe_wars";

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> WAR_DECLARED_SOUND = SOUND_EVENTS.register("war_declared",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MOD_ID, "war_declared")));

    public PeWarsClient(IEventBus modEventBus) {
        SOUND_EVENTS.register(modEventBus);
    }

    public static final Lazy<KeyMapping> OPEN_WAR_KEY = Lazy.of(() -> new KeyMapping(
            "key.pe_wars.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.categories.pe_wars"
    ));

    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_WAR_KEY.get());
        }
    }

    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_WAR_KEY.get().consumeClick()) {
                if (minecraft.player != null && minecraft.screen == null) {
                    minecraft.setScreen(new WarMenuScreen());
                }
            }
        }

        @SubscribeEvent
        public static void chat(ClientChatReceivedEvent event) {
            String message = event.getMessage().getString();
            // Важен порядок: более специфичные маркеры — раньше.
            if (message.startsWith(WarsClientApi.RAID_MARKER)) {
                event.setCanceled(true);
                WarsClientApi.handleRaidMessage(message);
                return;
            }
            if (message.startsWith(WarsClientApi.WAR_MARKER)) {
                event.setCanceled(true);
                WarsClientApi.handleWarMessage(message);
            }
        }
    }
}
