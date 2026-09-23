package fireproofghasts;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

public final class FireRules {
	private FireRules() {
	}

	public static boolean ignoresDamage(LivingEntity entity, DamageSource source) {
		Settings settings = FireproofConfig.get();
		return isProtected(entity, settings) && isIgnoredDamage(source, settings);
	}

	public static boolean preventsBurning(Entity entity) {
		Settings settings = FireproofConfig.get();
		return settings.get(Toggle.BURNING) && isProtected(entity, settings);
	}

	private static boolean isProtected(Entity entity, Settings settings) {
		// client-side entities only mirror the server
		if (!settings.get(Toggle.ENABLED) || !(entity.level() instanceof ServerLevel)) {
			return false;
		}
		if (isProtectedGhast(entity, settings)) {
			return true;
		}
		Entity vehicle = entity.getVehicle();
		return settings.get(Toggle.PROTECT_RIDERS) && vehicle != null && isProtectedGhast(vehicle, settings);
	}

	private static boolean isProtectedGhast(Entity entity, Settings settings) {
		if (!VersionCompat.isHappyGhast(entity)) {
			return false;
		}
		LivingEntity ghast = (LivingEntity) entity;
		if (!settings.get(ghast.isBaby() ? Toggle.PROTECT_GHASTLINGS : Toggle.PROTECT_ADULTS)) {
			return false;
		}
		// the harness sits in the body armor slot
		if (settings.get(Toggle.REQUIRE_HARNESS) && ghast.getItemBySlot(EquipmentSlot.BODY).isEmpty()) {
			return false;
		}
		return !settings.get(Toggle.REQUIRE_RIDER) || ghast.isVehicle();
	}

	private static boolean isIgnoredDamage(DamageSource source, Settings settings) {
		Toggle fireToggle = fireToggleFor(source);
		return (fireToggle != null && settings.get(fireToggle)) || isExtraDamageType(source.typeHolder(), settings);
	}

	private static Toggle fireToggleFor(DamageSource source) {
		if (source.is(DamageTypes.LAVA)) {
			return Toggle.LAVA;
		}
		if (source.is(DamageTypes.ON_FIRE)) {
			return Toggle.BURNING;
		}
		if (source.is(DamageTypeTags.IS_FIRE)) {
			return Toggle.FIRE;
		}
		return null;
	}

	private static boolean isExtraDamageType(Holder<DamageType> type, Settings settings) {
		if (settings.extraIds().contains(type.getRegisteredName())) {
			return true;
		}
		return !settings.extraTags().isEmpty()
				&& type.tags().anyMatch(tag -> settings.extraTags().contains(tag.location().toString()));
	}
}
