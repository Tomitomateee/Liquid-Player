package dev.afkcinematic.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Builds the settings screen with Cloth Config. Floats are shown as integer sliders
 * (e.g. distance in tenths of a block) because sliders feel nicer than text fields.
 */
public final class ConfigScreens {
	private ConfigScreens() {}

	public static Screen create(Screen parent) {
		CinematicConfig cfg = CinematicConfig.get();
		CinematicConfig def = new CinematicConfig();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("config.afkcinematic.title"))
				.setSavingRunnable(cfg::save);
		ConfigEntryBuilder e = builder.entryBuilder();

		ConfigCategory general = builder.getOrCreateCategory(Component.translatable("config.afkcinematic.general"));
		general.addEntry(e.startBooleanToggle(t("autoStart"), cfg.autoStart)
				.setDefaultValue(def.autoStart)
				.setTooltip(t("autoStart.tooltip"))
				.setSaveConsumer(v -> cfg.autoStart = v)
				.build());
		general.addEntry(e.startIntSlider(t("afkSeconds"), cfg.afkSeconds, 10, 600)
				.setDefaultValue(def.afkSeconds)
				.setTextGetter(v -> Component.literal(v + " s"))
				.setSaveConsumer(v -> cfg.afkSeconds = v)
				.build());

		ConfigCategory camera = builder.getOrCreateCategory(Component.translatable("config.afkcinematic.camera"));
		camera.addEntry(e.startIntSlider(t("cameraDistance"), Math.round(cfg.cameraDistance * 10), 20, 160)
				.setDefaultValue(Math.round(def.cameraDistance * 10))
				.setTextGetter(v -> Component.literal(String.format(Locale.ROOT, "%.1f", v / 10f)))
				.setSaveConsumer(v -> cfg.cameraDistance = v / 10f)
				.build());
		camera.addEntry(e.startIntSlider(t("orbitSpeed"), Math.round(cfg.orbitSpeed * 100), 10, 300)
				.setDefaultValue(Math.round(def.orbitSpeed * 100))
				.setTextGetter(v -> Component.literal(v + " %"))
				.setSaveConsumer(v -> cfg.orbitSpeed = v / 100f)
				.build());
		camera.addEntry(e.startIntSlider(t("barsPerShot"), cfg.barsPerShot, 1, 16)
				.setDefaultValue(def.barsPerShot)
				.setTooltip(t("barsPerShot.tooltip"))
				.setSaveConsumer(v -> cfg.barsPerShot = v)
				.build());

		ConfigCategory music = builder.getOrCreateCategory(Component.translatable("config.afkcinematic.music"));
		music.addEntry(e.startIntSlider(t("beatStrength"), Math.round(cfg.beatStrength * 100), 0, 200)
				.setDefaultValue(Math.round(def.beatStrength * 100))
				.setTextGetter(v -> Component.literal(v + " %"))
				.setSaveConsumer(v -> cfg.beatStrength = v / 100f)
				.build());
		music.addEntry(e.startIntSlider(t("musicThreshold"), Math.round(cfg.musicThreshold * 1000), 0, 100)
				.setDefaultValue(Math.round(def.musicThreshold * 1000))
				.setTooltip(t("musicThreshold.tooltip"))
				.setTextGetter(v -> Component.literal(String.format(Locale.ROOT, "%.3f", v / 1000f)))
				.setSaveConsumer(v -> cfg.musicThreshold = v / 1000f)
				.build());
		music.addEntry(e.startBooleanToggle(t("lyrics"), cfg.lyrics)
				.setDefaultValue(def.lyrics)
				.setSaveConsumer(v -> cfg.lyrics = v)
				.build());
		music.addEntry(e.startIntSlider(t("fontScale"), Math.round(cfg.fontScale * 10), 10, 40)
				.setDefaultValue(Math.round(def.fontScale * 10))
				.setTextGetter(v -> Component.literal(String.format(Locale.ROOT, "%.1fx", v / 10f)))
				.setSaveConsumer(v -> cfg.fontScale = v / 10f)
				.build());
		music.addEntry(e.startBooleanToggle(t("songInfo"), cfg.songInfo)
				.setDefaultValue(def.songInfo)
				.setSaveConsumer(v -> cfg.songInfo = v)
				.build());

		ConfigCategory look = builder.getOrCreateCategory(Component.translatable("config.afkcinematic.look"));
		look.addEntry(e.startBooleanToggle(t("letterbox"), cfg.letterbox)
				.setDefaultValue(def.letterbox)
				.setSaveConsumer(v -> cfg.letterbox = v)
				.build());
		look.addEntry(e.startBooleanToggle(t("vignette"), cfg.vignette)
				.setDefaultValue(def.vignette)
				.setSaveConsumer(v -> cfg.vignette = v)
				.build());

		return builder.build();
	}

	private static Component t(String key) {
		return Component.translatable("config.afkcinematic." + key);
	}
}
