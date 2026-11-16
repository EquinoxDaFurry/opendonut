# Transport check

A standalone diagnostic that proves the byte-level contract between the Fabric backend and the Velocity proxy
**without needing a Minecraft client**.

```powershell
.\tools\verify-transport.ps1
```

## What it checks

The OpenDonut bridge travels over Minecraft plugin messages, and the only channel a Fabric mod and a Velocity
plugin share is the custom-payload field. That field has a contract:

> **The payload field carries the frame verbatim. Byte 0 of the payload is byte 0 of the frame — no length
> prefix, no padding, nothing in front of it.**

Velocity is a pure pass-through here: `PluginMessagePacket.decode` is `readRetainedSlice(readableBytes())` and
`encode` is `writeBytes(...)`, and `ServerConnection.sendPluginMessage` puts a plugin's bytes straight into the
field. So the contract is really "both ends must agree that the field *is* the frame", and the check is
whether a frame survives Velocity's own encode/decode cycle unchanged and is then accepted by the shared
decoder.

The tool drives Velocity's real `PluginMessagePacket` in both directions and asserts:

1. **backend → proxy** — what a plugin receives as `PluginMessageEvent.getData()` is byte-identical to the
   frame, and the shared decoder accepts it;
2. **proxy → backend** — what a backend receives in the payload field is byte-identical to the frame, and the
   shared decoder accepts it;
3. **the old, broken framing** — a frame wrapped in a VarInt length prefix (what
   `FriendlyByteBuf.writeByteArray` produces) is still rejected, and the diagnostic names the cause instead of
   only the symptom.

## Why it exists

An early revision of `BridgePayload` wrapped the frame in a length prefix on the sending side only. Every
frame then arrived as `[len][ODNT…]`, the proxy's magic check read the length byte instead of `'O'`, and every
transfer was refused with `BAD_MAGIC` — a message that points at the magic bytes and says nothing about
framing. Diagnosing it needed a real player connected to a real proxy, and reproducing it took a client
session.

This tool turns that class of bug into a five-second check, and it is the reason the contract is now stated
in three places: in the codec's javadoc, in `docs/architecture.md`, and here.

## Caveats

* It uses Velocity's **internal** classes (`com.velocitypowered.proxy.*`), which are not API. It is therefore a
  development diagnostic and deliberately not part of the Gradle build. If a future Velocity release changes
  those internals, this file stops compiling — that is a signal to re-read the packet classes with `javap`, not
  a failure of the project build.
* It checks the *transport*, not the routing decision. `tests/README.md` covers what still needs a real client:
  the transfer itself, arrival position, and the failure paths.
