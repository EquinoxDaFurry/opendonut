package net.opendonut.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * The capture side of player-data sync, against the real Minecraft NBT classes.
 *
 * <p>Two properties matter here and are pinned by these tests: the {@code NOT_SYNCED} blacklist really removes
 * position, dimension and the other keys that must never travel (a store is a database and must not hold where
 * players were), and the NBT encode/decode round trip is lossless, because the whole feature is only as good as
 * the bytes that cross the wire.
 */
class PlayerDataCaptureTest {

	/**
	 * Builds a tag shaped like a player file: the keys that must travel, and the keys that must not.
	 *
	 * @return the tag
	 */
	private static CompoundTag playerLikeTag() {
		CompoundTag tag = new CompoundTag();

		// --- keys that MUST synchronise ---
		CompoundTag inventory = new CompoundTag();
		CompoundTag slot0 = new CompoundTag();
		slot0.putString("id", "minecraft:diamond_sword");
		slot0.putByte("count", (byte) 1);
		CompoundTag components = new CompoundTag();
		components.putString("minecraft:custom_name", "\"Excalibur\"");
		slot0.put("components", components);
		ListTag items = new ListTag();
		items.add(slot0);
		inventory.put("Items", items);
		tag.put("Inventory", inventory);

		CompoundTag ender = new CompoundTag();
		ender.put("Items", new ListTag());
		tag.put("EnderItems", ender);

		tag.putFloat("Health", 17.0f);
		tag.putInt("foodLevel", 6);
		tag.putFloat("foodSaturationLevel", 3.5f);
		tag.putInt("XpLevel", 42);
		tag.putInt("XpTotal", 51234);
		tag.putFloat("XpP", 0.25f);
		tag.putInt("SelectedItemSlot", 3);

		// --- keys that must NEVER synchronise ---
		ListTag pos = new ListTag();
		pos.add(net.minecraft.nbt.DoubleTag.valueOf(1034.5));
		pos.add(net.minecraft.nbt.DoubleTag.valueOf(69.0));
		pos.add(net.minecraft.nbt.DoubleTag.valueOf(658.5));
		tag.put("Pos", pos);
		tag.put("Rotation", new ListTag());
		tag.put("Motion", new ListTag());
		tag.putString("Dimension", "minecraft:overworld");
		tag.putInt("PortalCooldown", 300);
		tag.putInt("playerGameType", 1);
		tag.putString("UUID", "00000000-0000-0000-0000-000000000042");
		tag.put("vehicle", new CompoundTag());
		tag.putString("seenCredits", "true");

		return tag;
	}

	@Nested
	@DisplayName("stripNotSynced")
	class Strip {

		@Test
		@DisplayName("every NOT_SYNCED key is removed and every gameplay key is kept")
		void strips() {
			CompoundTag tag = playerLikeTag();
			CompoundTag stripped = PlayerDataCapture.stripNotSynced(tag);

			for (String key : PlayerDataCapture.NOT_SYNCED) {
				assertFalse(stripped.contains(key), "stripped tag must not contain '" + key + "'");
			}
			for (String key : new String[] {
					"Inventory", "EnderItems", "Health", "foodLevel", "foodSaturationLevel",
					"XpLevel", "XpTotal", "XpP", "SelectedItemSlot"}) {
				assertTrue(stripped.contains(key), "stripped tag must keep '" + key + "'");
			}
		}

		@Test
		@DisplayName("the input tag is not mutated")
		void doesNotMutate() {
			CompoundTag tag = playerLikeTag();
			PlayerDataCapture.stripNotSynced(tag);
			assertTrue(tag.contains("Pos"), "the original must still hold Pos");
			assertTrue(tag.contains("Dimension"));
			assertTrue(tag.contains("UUID"));
		}

		@Test
		@DisplayName("stripping is idempotent, so applying a hand-crafted snapshot cannot smuggle a position in")
		void idempotent() {
			CompoundTag once = PlayerDataCapture.stripNotSynced(playerLikeTag());
			CompoundTag twice = PlayerDataCapture.stripNotSynced(once);
			assertEquals(once, twice);
		}

		@Test
		@DisplayName("a tag with only forbidden keys strips to an empty compound")
		void allForbidden() {
			CompoundTag tag = new CompoundTag();
			tag.putString("Dimension", "minecraft:the_nether");
			tag.put("Pos", new ListTag());
			CompoundTag stripped = PlayerDataCapture.stripNotSynced(tag);
			assertTrue(stripped.isEmpty());
		}
	}

	@Nested
	@DisplayName("NBT encode/decode")
	class Nbt {

		@Test
		@DisplayName("a player-shaped tag round trips losslessly through the wire encoding")
		void roundTrip() throws Exception {
			CompoundTag tag = PlayerDataCapture.stripNotSynced(playerLikeTag());
			byte[] encoded = PlayerDataCapture.encodeNbt(tag);
			CompoundTag decoded = PlayerDataCapture.decodeNbt(encoded);
			assertEquals(tag, decoded);
		}

		@Test
		@DisplayName("item components inside the inventory survive exactly")
		void componentsSurvive() throws Exception {
			CompoundTag tag = playerLikeTag();
			byte[] encoded = PlayerDataCapture.encodeNbt(tag);
			CompoundTag decoded = PlayerDataCapture.decodeNbt(encoded);
			CompoundTag inventory = decoded.getCompound("Inventory").orElseThrow();
			ListTag items = inventory.getList("Items").orElseThrow();
			CompoundTag slot0 = items.getCompound(0).orElseThrow();
			assertEquals("minecraft:diamond_sword", slot0.getString("id").orElseThrow());
			assertEquals("\"Excalibur\"",
					slot0.getCompound("components").orElseThrow()
							.getString("minecraft:custom_name").orElseThrow());
		}

		@Test
		@DisplayName("garbage bytes are an IOException, not a crash with a wrong type")
		void garbage() {
			org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
					() -> PlayerDataCapture.decodeNbt(new byte[] {1, 2, 3, 4, 5}));
		}

		@Test
		@DisplayName("an empty compound round trips")
		void empty() throws Exception {
			CompoundTag tag = new CompoundTag();
			byte[] encoded = PlayerDataCapture.encodeNbt(tag);
			assertArrayEquals(encoded, PlayerDataCapture.encodeNbt(PlayerDataCapture.decodeNbt(encoded)));
			assertEquals(tag, PlayerDataCapture.decodeNbt(encoded));
		}

		@Test
		@DisplayName("string tags with unicode survive")
		void unicode() throws Exception {
			CompoundTag tag = new CompoundTag();
			tag.put("CustomName", StringTag.valueOf("M\u00f6rder \u4e2d\u6587 \u0627\u0644\u0639\u0631\u0628\u064a\u0629"));
			CompoundTag decoded = PlayerDataCapture.decodeNbt(PlayerDataCapture.encodeNbt(tag));
			assertEquals("M\u00f6rder \u4e2d\u6587 \u0627\u0644\u0639\u0631\u0628\u064a\u0629",
					decoded.getString("CustomName").orElseThrow());
		}

		@Test
		@DisplayName("the NOT_SYNCED list covers position, dimension and entity references")
		void blacklistCoversTheDangerousKeys() {
			List<String> required = List.of(
					"Pos", "Rotation", "Motion", "Dimension", "PortalCooldown",
					"playerGameType", "vehicle", "Passengers", "UUID");
			for (String key : required) {
				assertTrue(PlayerDataCapture.NOT_SYNCED.contains(key),
						"NOT_SYNCED must contain '" + key + "'");
			}
			assertTrue(PlayerDataCapture.NOT_SYNCED.size() >= 10,
					"the blacklist should be explicit and generous, not a token list");
		}
	}
}
