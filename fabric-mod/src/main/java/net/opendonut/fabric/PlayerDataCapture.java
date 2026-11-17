package net.opendonut.fabric;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Captures a player's synchronised state into NBT and applies it back.
 *
 * <h2>What is captured</h2>
 * The whole player, via the server's own serialiser: {@code player.saveWithoutId(...)} writes exactly the
 * compound Minecraft itself writes to {@code world/players/<uuid>.dat}, so inventory (all slots, armour,
 * off-hand), the ender chest, experience, health, hunger, active effects, attributes, equipment, the
 * selected hotbar slot and everything a mod attached all travel with one call — and stay correct when
 * Minecraft changes the format, because the mod never names a field.
 *
 * <h2>What is stripped</h2>
 * {@link #NOT_SYNCED} is the explicit list of keys that must <em>not</em> move: position, rotation, motion,
 * dimension and portal cooldown (the proxy places the player; a dimension must never cross), game mode (the
 * destination server's own mode wins), vehicles and passengers (entity transport is a separate, unbuilt
 * feature — applying a vehicle id that does not exist on the destination would glitch), and a few
 * server-state keys that reference positions in other dimensions. Everything else travels.
 *
 * <h2>Why the strip happens on both sides</h2>
 * The snapshot is stripped at capture time so the store never holds position or dimension at all (a privacy
 * property: the store is a database, and it should not contain where players were), and the same strip is
 * applied again at apply time, so even a hand-crafted snapshot cannot smuggle a position in.
 *
 * <h2>Applying</h2>
 * {@code player.load(...)} re-reads the compound through the same {@code ValueInput} API the server uses at
 * login, so every field is interpreted by the code that wrote it. Because the volatile keys are absent, a
 * missing key simply keeps the player's current value — the placement the proxy already gave them. After
 * the load, the client is re-synchronised explicitly (inventory, health, food, XP, held slot, effects,
 * abilities), because the client was told about its login state and would otherwise keep showing the old
 * one until the next change.
 */
final class PlayerDataCapture {

	/**
	 * The player-NBT keys that must never travel between servers.
	 *
	 * <p>Position, rotation, motion, dimension, fall state and portal cooldown belong to the transfer itself,
	 * which the proxy handles. Game mode belongs to the destination. Vehicles, passengers and the respawn
	 * anchor reference entities or dimensions this server may not have. Death location and the "seen
	 * credits" flag are per-world cosmetics.
	 */
	static final Set<String> NOT_SYNCED = Set.of(
			"Pos", "Rotation", "Motion", "Dimension", "FallDistance", "FallFlying", "PortalCooldown",
			"playerGameType", "previousPlayerGameType",
			"vehicle", "RootVehicle", "Passengers",
			"respawn", "LastDeathLocation", "entered_nether_pos", "seenCredits",
			"warden_spawn_tracker", "raid_omen_position",
			"UUID");

	private PlayerDataCapture() {
	}

	/**
	 * Captures a player's synchronised state.
	 *
	 * @param player the player
	 * @param registries the server's registry access, needed to encode item components such as enchantments
	 * @return the stripped compound
	 */
	static CompoundTag capture(ServerPlayer player, HolderLookup.Provider registries) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		player.saveWithoutId(out);
		return stripNotSynced(out.buildResult());
	}

	/**
	 * Returns a copy of the tag with every {@link #NOT_SYNCED} key removed.
	 *
	 * @param tag the tag, not mutated
	 * @return the stripped copy
	 */
	static CompoundTag stripNotSynced(CompoundTag tag) {
		CompoundTag copy = tag.copy();
		for (String key : NOT_SYNCED) {
			copy.remove(key);
		}
		return copy;
	}

	/**
	 * Serialises a compound to the uncompressed NBT bytes that travel inside a snapshot envelope.
	 *
	 * @param tag the tag
	 * @return the bytes
	 * @throws IOException if serialisation fails, which for an in-memory stream cannot happen
	 */
	static byte[] encodeNbt(CompoundTag tag) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(256, tag.size() * 32));
		NbtIo.write(tag, new DataOutputStream(buffer));
		return buffer.toByteArray();
	}

	/**
	 * Deserialises NBT bytes written by {@link #encodeNbt}.
	 *
	 * @param bytes the bytes
	 * @return the compound
	 * @throws IOException if the bytes are not a valid compound. Minecraft signals malformed input with the
	 *                     unchecked {@code ReportedNbtException}; it is wrapped here so the caller's contract —
	 *                     a checked {@code IOException} — is real
	 */
	static CompoundTag decodeNbt(byte[] bytes) throws IOException {
		try {
			return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("malformed NBT: " + e.getMessage(), e);
		}
	}

	/**
	 * Applies stored NBT bytes to a live player, then re-synchronises the client.
	 *
	 * <p>Runs on the server thread, from the bridge's message handler.
	 *
	 * @param player the player who just arrived
	 * @param nbt the stored bytes
	 * @param registries the server's registry access
	 * @throws IOException if the bytes do not decode
	 */
	static void apply(ServerPlayer player, byte[] nbt, HolderLookup.Provider registries) throws IOException {
		CompoundTag tag = stripNotSynced(decodeNbt(nbt));

		Set<Holder<MobEffect>> effectsBefore = new HashSet<>(player.getActiveEffectsMap().keySet());

		player.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));

		// The client was told about its login state; push the applied state explicitly, or the player sees
		// an empty inventory and full health until the next change.
		player.inventoryMenu.broadcastFullState();
		player.connection.send(new ClientboundSetHealthPacket(
				player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
		player.connection.send(new ClientboundSetExperiencePacket(
				player.experienceProgress, player.totalExperience, player.experienceLevel));
		player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
		for (MobEffectInstance effect : player.getActiveEffects()) {
			player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
		}
		for (Holder<MobEffect> removed : effectsBefore) {
			if (!player.getActiveEffectsMap().containsKey(removed)) {
				player.connection.send(new ClientboundRemoveMobEffectPacket(player.getId(), removed));
			}
		}
		player.sendPostEffects();
		player.connection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
	}
}
