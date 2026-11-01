# AutomatedTrains

A Paper plugin that couples ordinary vanilla minecarts into automated trains. Trains
follow the rails by themselves, stop at stations, switch junctions by destination, and
keep clear of each other. The carts stay plain vanilla `Minecart` entities.

## Features

- **Coupling:** sneak and use an iron chain on two minecarts to join them into a train.
- **Stations:** a `[Station]` sign stops the train, waits, sets its destination, and moves it on.
- **Routing:** a `[Route]` sign turns a junction toward the train's destination. One train
  crosses a junction at a time.
- **Train-ahead detection:** a train stops behind a stopped train on the same track and moves
  off when the way is clear. Trains that follow each other round tight turns do not deadlock.
- **Chunk loading:** trains keep running through unloaded chunks and reload with the world.
- **Trust:** a train belongs to whoever coupled it, and its owner can trust other players.
- **Radio announcements (optional):** with [RadioNetwork](../RadioNetwork) installed, stations
  announce arrivals and departures over a radio channel.

## Requirements

| Server | Java | `api-version` |
|---|---|---|
| Paper **26.3** | **25** | `26.3` |
| Paper **26.2** (default build) | **25** | `26.2` |
| Paper **26.1.2** | **25** | `26.1` |
| Paper **1.21.11** (legacy) | **21** | `1.21` |

Maven is used for building. Paper 26.x class files need a JDK 25 toolchain, even for the
legacy build, which cross-targets Java 21.

### Dependencies

None are required. Paper is the only thing the plugin needs, and it bundles no other libraries.
[RadioNetwork](../RadioNetwork) is an optional soft dependency. With it installed, stations can
announce arrivals and departures over a radio channel.

## Building

Paper 26.3:

```bash
mvn clean package -Dpaper.api.version=26.3.build.236-stable -Dplugin.api.version=26.3
```

Paper 26.2 (default):

```bash
mvn clean package
```

Paper 26.1.2:

```bash
mvn clean package -Dpaper.api.version=26.1.2.build.74-stable -Dplugin.api.version=26.1
```

Paper 1.21.11 (legacy):

```bash
mvn clean package -Plegacy-1.21
```

The jar is written to `target/`. Copy it into the server's `plugins/` folder.

## Using it

### Coupling

Sneak and right-click one minecart with an iron chain, then do the same to a second cart
within 10 blocks. The first cart becomes the lead. Repeat with more carts to lengthen the
train. To start it, sneak and double right-click the lead cart.

### Signs

Both signs sit **two blocks below** the rail the train runs on.

**Station**

```
[Station]
Dest: Port
Port
Northern Line
```

| Line | Meaning |
|---|---|
| 1 | `[Station]` |
| 2 | Optional. `Dest: <name>` sets the train's destination when it stops here |
| 3 | Station name (used in radio announcements) |
| 4 | Radio channel / line name. Leave empty for no announcement |

**Route**

Place it two blocks under a plain rail at the junction.

```
[Route]
Spawn: left
Mine: right
*: straight
```

Directions are relative to the way the train is heading: `left`, `right`, or `straight`.
`*` (or `default`) covers every destination not listed. With no match and no default, the
train goes straight on.

### Commands

Commands act on the train you are looking at, sitting in, or standing next to.

| Command | What it does |
|---|---|
| `/train info` | Show its owner, destination and trusted players |
| `/train setdest <name>` | Set its destination |
| `/train trust <player>` | Let another player use it |
| `/train untrust <player>` | Remove that trust |
| `/train clear` | Unlink the train and wipe its data |
| `/train move <forward\|back>` | Set the direction it travels |
| `/train stop` | Stop it until it is started again |

### Permissions

| Permission | Default | |
|---|---|---|
| `automatedtrains.use` | everyone | Use `/train` |
| `automatedtrains.couple` | everyone | Couple minecarts |
| `automatedtrains.admin` | op | Override ownership on any train |

## Configuration

See [`config.yml`](src/main/resources/config.yml). The main settings are `speed`, `spacing`,
`station-wait-seconds`, `train-stop-distance`, `junction-lock-timeout-seconds`, and the
`announcements` templates. Changes apply on the next start or reload.

## Known limitations

- Two trains that meet head-on on a single track stay held until someone intervenes, for
  example with `/train stop` or `/train move`.
- Radio announcements need RadioNetwork. Without it the setting does nothing.
