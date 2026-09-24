# Simple P2P

A Minecraft 1.20.1 Forge mod that lets players join a world by sharing a **room code**
instead of configuring IP addresses and port forwarding. Both sides automatically download
and launch the official [EasyTier](https://github.com/EasyTier/EasyTier) client to build a
virtual network.

## When is it needed

Both the client and the server need this mod **only when you want to use the room-code
joining feature**. For normal single-player sessions or ordinary multiplayer, installing it
is not required.

## Features

- The server runs `/p2p open` to create a room code; friends type that code into the
  "Add Server" or "Direct Connect" address field to join
- Automatically downloads, installs and launches the EasyTier official client, so no manual
  virtual adapter or port-forwarding setup is required
- Fetches community public nodes, measures their latency, and connects to the reachable ones
- Connection progress and failure reasons are shown as in-game toasts in the top right corner
- A single jar works for both client and server

## Requirements

- Minecraft 1.20.1 with Forge 47 or newer
- Windows / Linux / macOS (no administrator privileges required on Windows)
- Network access to EasyTier community public nodes

## Installation

Put `SimpleP2P-1.0.0.jar` into the `mods/` folder. Install it on both sides only if you intend
to use the room-code feature.

## Usage

### Host (server side)

1. Enter a single-player world and click "Open to LAN", or start a dedicated server
2. Run `/p2p open`
3. Send the room code shown in chat to your friends

If the actual MC port is not 25565, run `/p2p setport <port>` before opening the room.

### Join (client side)

1. Go to "Multiplayer" - "Add Server" or "Direct Connect"
2. Enter the room code as the server address
3. Once the top-right toast reports that the local forward is established, the game connects

## Commands

| Command | Description |
| --- | --- |
| `/p2p open` | Open a room and generate a room code |
| `/p2p close` | Close the room |
| `/p2p status` | Show room and network status |
| `/p2p setport <port>` | Set the local MC port (default 25565) |
| `/p2p settoken <token>` | Set an OpenP2P token (optional fallback channel) |
| `/p2p sslignore on\|off` | Whether to skip SSL verification when downloading core files |

## How it works

1. The room code derives the EasyTier network name and secret: `sp2p-<roomcode>` and the
   SHA-256 hex digest of the room code
2. Both sides start the EasyTier client and join the same virtual network
3. The server embeds its real MC port in the EasyTier hostname and exposes it with
   `--tcp-whitelist`
4. The client uses `easytier-cli port-forward` to forward a local port to the server's MC port
5. Minecraft connects to `127.0.0.1:<local port>`, so virtual adapter routing is not involved
   and administrator privileges are not needed

The EasyTier arguments follow the ET mode of
[MinecraftConnectTool](https://github.com/MCZLF/MinecraftConnectTool), including `--no-tun`,
`--use-smoltcp`, `--compression zstd` and the KCP/QUIC proxies, to cope with high-latency and
lossy links.

## Configuration

`config/simplep2p.json` is generated on first launch. It contains the node list URL, download
sources and timeout values.

A `mods/simplep2p/` folder is also created to store the downloaded EasyTier client and its
runtime log (`easytier.log`).

## Building

```bash
./gradlew build
```

Output: `build/libs/SimpleP2P-1.0.0.jar`

## Known limitations

- Networking depends on EasyTier community public nodes, so their availability affects the
  success rate
- When both sides have strict NAT types the traffic goes through a relay, which increases latency
- If no public node is reachable, the mod falls back to the default node in the config
