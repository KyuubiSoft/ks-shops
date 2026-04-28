# KS-Shops - Complete Documentation

A standalone NPC-based shop system for Hytale servers, with player-owned shops, admin shops, paid directory listings, and a rental-station system (fixed-price + auction).

- **Plugin ID:** `kyuubisoft:shops`
- **Main class:** `com.kyuubisoft.shops.ShopPlugin`
- **Server data folder:** `<server>/mods/kyuubisoft_shops/`
- **DB:** SQLite (default) at `shops.db`, MySQL optional
- **Economy:** via VaultUnlocked (auto-detected, with retry)
- **Localisation:** 9 languages shipped (en, de, fr, es, pt, ru, pl, tr, it)

---

## Table of Contents

1. [Quick Start](#1-quick-start)
2. [Configuration](#2-configuration)
3. [Permissions](#3-permissions)
4. [Player Commands](#4-player-commands-ksshop)
5. [Admin Commands](#5-admin-commands-kssa)
6. [UI Surfaces](#6-ui-surfaces)
7. [Rental System](#7-rental-system)
8. [Directory & Listings](#8-directory--listings)
9. [NPC System](#9-npc-system)
10. [Database Schema](#10-database-schema)
11. [Events / Integration](#11-events--integration-api)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Quick Start

### Dependencies
- `TheNewEconomy:VaultUnlocked` (optional, but transactions need an economy)
- `Ecotale:Ecotale` (recommended provider; any VaultUnlocked-compatible provider works)

### First steps

1. Copy `KyuubiSoftShops-1.0.0.jar` to `<server>/mods/`
2. Start the server. The plugin extracts `mods/kyuubisoft_shops/config.json` on first load.
3. Grant the basic player permissions (LuckPerms example):
   ```
   lp group default permission set ks.shop.user.create true
   lp group default permission set ks.shop.user.edit true
   lp group default permission set ks.shop.user.browse true
   lp group default permission set ks.shop.user.collect true
   lp group default permission set ks.shop.user.rate true
   lp group default permission set ks.shop.user.delete true
   lp group default permission set ks.rental.rent true
   lp group default permission set ks.rental.bid true
   ```
4. Play test: `/ksshop create MyShop` then F-key the spawned NPC to open the editor.

### Logs to watch on startup

```
[KyuubiSoft Shops] Setting up KS-Shops...
[KyuubiSoft Shops] Config loaded: ...
[KyuubiSoft Shops] Database initialized (sqlite)
[KyuubiSoft Shops] Economy via VaultUnlocked direct (Ecotale)   <- or "attached on retry" within 20s
[KyuubiSoft Shops] Loaded N shops (...)
[KyuubiSoft Shops] Loaded N rental slot(s)
[KyuubiSoft Shops] KS-Shops ready. N shops loaded.
```

---

## 2. Configuration

File: `<server>/mods/kyuubisoft_shops/config.json`

### `features`
Toggles for major subsystems.

```json
"features": {
  "playerShops": true,
  "adminShops": true,
  "directory": true,
  "ratings": true,
  "notifications": true,
  "shopOfTheWeek": false,
  "rentSystem": false,
  "claimsIntegration": true
}
```

### `playerShops`
```json
"playerShops": {
  "maxShopsPerPlayer": 3,
  "maxItemsPerShop": 45,
  "creationCost": 500,
  "requireClaim": false,
  "allowNpcCustomization": true,
  "nameMinLength": 3,
  "nameMaxLength": 24,
  "descriptionMaxLength": 100,
  "maxTagsPerShop": 5,
  "listingEnabled": true,
  "listingPricePerDay": 100,
  "listingMinDays": 1,
  "listingMaxDays": 30,
  "listingFreeDaysOnCreate": 7
}
```

- `listingFreeDaysOnCreate` is granted **once per player lifetime**. Persists in `shop_player_flags`, survives shop delete + recreate.

### `adminShops`
```json
"adminShops": {
  "unlimitedStock": true,
  "allowBuyAndSell": true,
  "defaultNpcSkin": ""
}
```

### `economy`
Display only - the actual provider is auto-detected via VaultUnlocked.

```json
"economy": {
  "provider": "auto",
  "currencyName": "Gold",
  "currencySymbol": "",
  "maxPrice": 1000000,
  "minPrice": 1
}
```

### `tax`
```json
"tax": {
  "enabled": false,
  "buyTaxPercent": 5.0,
  "sellTaxPercent": 5.0,
  "showTaxInPrice": true,
  "taxRecipient": "server"
}
```

### `rent` (legacy shop-rent system, NOT rental stations)
```json
"rent": {
  "enabled": false,
  "costPerDay": 50.0,
  "gracePeriodDays": 3,
  "autoCloseOnExpire": true,
  "autoDeleteOnUnpaidDays": 7,
  "rentExemptForNewShopsDays": 7,
  "rentNotificationBeforeDays": 1,
  "maxCycleDays": 30
}
```

### `ratings`
```json
"ratings": {
  "enabled": true,
  "minStars": 1,
  "maxStars": 5,
  "allowComments": true,
  "commentMaxLength": 100,
  "cooldownMinutes": 60
}
```

### `notifications`
```json
"notifications": {
  "enabled": true,
  "batchOnLogin": true,
  "maxStoredPerPlayer": 50,
  "soundEnabled": true
}
```

### `npc`
```json
"npc": {
  "defaultEntityId": "NPC_Shopkeeper",
  "defaultSkinUsername": "",
  "lookAtPlayer": true,
  "interactionRange": 5.0,
  "showNameplate": true,
  "spawnDelaySecondsOnJoin": 3,
  "skinRetryDelaySeconds": 5
}
```

- `spawnDelaySecondsOnJoin` defers the per-world NPC spawn burst by N seconds after the first player joins, so the client cosmetic subsystem is ready before skins push (clamped 0..60).
- `skinRetryDelaySeconds` retries a failed PlayerDB skin fetch once (clamped 0..60).

### `directory`
```json
"directory": {
  "shopsPerPage": 9,
  "defaultSortBy": "rating",
  "showEmptyShops": false,
  "showClosedShops": false
}
```

### `featured`
```json
"featured": {
  "enabled": false,
  "cost": 1000,
  "durationDays": 7,
  "maxFeaturedSlots": 3,
  "autoFeatureTopRated": false,
  "autoFeatureMinRating": 4.5,
  "autoFeatureMinSales": 50
}
```

### `rentalStations`
```json
"rentalStations": {
  "enabled": true,
  "defaultPricePerDay": 100,
  "defaultMaxDays": 7,
  "defaultMinBid": 500,
  "defaultBidIncrement": 10,
  "defaultAuctionDurationMinutes": 60,
  "maxConcurrentRentalsDefault": 1,
  "onEmptyAuction": "RESTART",
  "auctionAntiSnipingSeconds": 30,
  "auctionEndingSoonSeconds": 60,
  "extendMaxDays": 7,
  "releaseEarlyGoldRefundFraction": 0.0,
  "rentalShopsListFree": true
}
```

- `onEmptyAuction`: `"RESTART"` (default) starts a new round, `"VACANT"` waits for admin re-arm, `"DELETE"` removes the slot. Also governs the post-rental-expiry path - after a rental ends, an AUCTION slot auto-rearms when set to RESTART.
- `auctionAntiSnipingSeconds`: bid in last N seconds extends auction by N seconds.
- `rentalShopsListFree`: rental-backed shops skip the directory listing fee, listing window mirrors the rental window.

### `database`
```json
"database": {
  "type": "sqlite",
  "sqlitePath": "shops.db",
  "mysql": {
    "host": "localhost",
    "port": 3306,
    "database": "hytale",
    "username": "root",
    "password": ""
  }
}
```

### `categories`
List of shop categories used for filtering in browse + the editor dropdown.

```json
"categories": [
  { "id": "weapons",   "displayName": "Weapons",   "icon": "Iron_Sword",          "sortOrder": 1 },
  { "id": "armor",     "displayName": "Armor",     "icon": "Iron_Chestplate",     "sortOrder": 2 },
  ...
]
```

### `itemBlacklist`
List of itemIds that cannot be added to any shop. Modified live via `/kssa blacklist`.

---

## 3. Permissions

### Defaults

The plugin code passes `default = true` to every player-side permission check. With **LuckPerms** loaded, this default is ignored - LuckPerms answers `false` to any unset permission. So in practice, **every permission must be explicitly granted**.

### Wildcard caveat

A wildcard like `*` or `ks.*` makes every `hasPermission` check return `true`. To prevent OPs from silently inheriting privileged listing/limit behaviour, the plugin uses a **wildcard sentinel probe** for these specific nodes:
- `ks.shop.list.permanent`
- `ks.shop.list.free`
- `ks.shop.limit.shops.N`
- `ks.shop.limit.items.N`
- `ks.rental.limit.N`

If the sentinel matches (= wildcard detected), the plugin treats the grant as "no specific grant" and falls back to the global config default. To grant these to an OP intentionally, deny the wildcard for `ks.shop.*` first or grant on a non-OP rank.

### Player permissions

| Node | Grants |
|---|---|
| `ks.shop.user.create` | `/ksshop create` |
| `ks.shop.user.edit` | `/ksshop edit`, `open`, `close`, `rename`, `delete`, `deposit`, `transfer` |
| `ks.shop.user.browse` | `/ksshop browse`, `search`, `visit` |
| `ks.shop.user.rate` | `/ksshop rate` |
| `ks.shop.user.collect` | `/ksshop collect` |
| `ks.shop.user.delete` | `/ksshop delete` (own shop) |
| `ks.rental.rent` | Rent fixed-price slots |
| `ks.rental.bid` | Bid in auctions |

### Limit overrides (explicit grants only)

| Node | Effect |
|---|---|
| `ks.shop.limit.shops.N` | Max N owned shops (overrides `playerShops.maxShopsPerPlayer`) |
| `ks.shop.limit.items.N` | Max N items per shop (overrides `playerShops.maxItemsPerShop`) |
| `ks.rental.limit.N` | Max N concurrent rentals (overrides `rentalStations.maxConcurrentRentalsDefault`) |

Highest matched N wins. Scan range: 1..200.

### Listing perks

| Node | Effect |
|---|---|
| `ks.shop.list.permanent` | Shop is forever-listed without paying |
| `ks.shop.list.free` | Pay 0g for any listing duration (still day-clamped) |

### Admin

| Node | Grants |
|---|---|
| `ks.shop.admin` | All `/kssa *` commands (admin shops, rental admin, force expire, etc.) |

---

## 4. Player Commands (`/ksshop`)

Aliases: `/shop`, `/market`

| Command | Description |
|---|---|
| `/ksshop help` | Short getting-started hint + command list |
| `/ksshop create <name>` | Create a new player shop at your position. Costs `playerShops.creationCost` |
| `/ksshop edit` | Open the drag-and-drop editor for the nearest owned shop |
| `/ksshop delete <nameOrId> [confirm]` | Delete an owned shop. Two-step confirm; items + balance go to mailbox |
| `/ksshop open <nameOrId>` | Set shop to open (visible to buyers). Nameplate updates live |
| `/ksshop close <nameOrId>` | Set shop to closed. NPC stays visible with `[CLOSED]` suffix; buyers see "Shop is closed" message |
| `/ksshop rename <newName>` | Rename the **nearest** owned shop (auto-resolved by proximity) |
| `/ksshop browse` | Open the public directory with cards, filters, sort, pagination |
| `/ksshop search <query>` | Item or shop-name search. Opens directory in search mode |
| `/ksshop visit <nameOrId>` | Open browse view of a specific shop directly |
| `/ksshop rate <nameOrId> <stars>` | Rate a shop 1..5 stars. Cooldown configurable |
| `/ksshop history` | Transaction log for your shops |
| `/ksshop notifications` | List of your stored notifications |
| `/ksshop collect` | Collect any pending shop balance to your account |
| `/ksshop deposit <amount>` | Add funds to the nearest owned shop's buyback pool (so the shop can buy items off players) |
| `/ksshop stats` | Aggregate stats: shops owned, total revenue, total tax, sales, avg rating, pending mailbox |
| `/ksshop transfer <nameOrId> <playerName>` | Send a transfer request to another online player |
| `/ksshop accepttransfer` | Accept a pending incoming transfer |
| `/ksshop declinetransfer` | Decline a pending incoming transfer |
| `/ksshop list <days>` | Buy or extend the directory listing for the nearest owned shop |
| `/ksshop myshops` | Open the directory in "your shops" mode (no listing filter) |
| `/ksshop myrentals` | Open self-service page for your rentals (extend, release early) |
| `/ksshop releaserental <slotId>` | Release a rental slot early. Items mailed back, gold refund per config fraction |
| `/ksshop rentalstations` | Browse rental slots in your current world |

### Argument types
- `<nameOrId>` accepts both UUID and case-insensitive shop name
- `<slotId>` requires UUID (find via `/kssa listrentals`)

---

## 5. Admin Commands (`/kssa`)

Alias: `/ksshopadmin`. All require `ks.shop.admin`.

### Shop management

| Command | Description |
|---|---|
| `/kssa admin` | Open the admin panel UI (tabs: Shops, Players, Transactions, Rentals) |
| `/kssa createadmin <title>` | Create an admin shop at your position. No owner, unlimited stock |
| `/kssa editadmin` | Edit the nearest admin shop (drag-and-drop, no owner check) |
| `/kssa deleteadmin <shopId>` | Delete an admin shop. UUID only |
| `/kssa deleteplayer <shopId>` | Delete a player shop (force). Owner gets mailbox refund |
| `/kssa closeplayer <shopId> [reason]` | Force-close a player shop. Optional reason text |
| `/kssa openplayer <shopId>` | Re-open a force-closed player shop |
| `/kssa settax <percent>` | Live-adjust both buy + sell tax percent. Persists to config |
| `/kssa feature <shopId> <days>` | Mark a shop as Featured for N days |
| `/kssa stats` | Server-wide shop stats |
| `/kssa log` | Recent admin audit log |
| `/kssa blacklist <add\|remove\|list> [itemId]` | Manage item blacklist |
| `/kssa reload` | Reload config + i18n without restart |

### NPC management

| Command | Description |
|---|---|
| `/kssa respawnnpcs` | Despawn + respawn all shop NPCs in current world (recovery from stuck/missing) |
| `/kssa deletenearest` | Delete the nearest shop + its NPC |

### Rental stations

| Command | Description |
|---|---|
| `/kssa createrental <displayName> [pricePerDay] [maxDays]` | Create a fixed-price rental slot at your position |
| `/kssa createrentalauction <displayName> [minBid] [bidIncrement] [durationMinutes] [rentalDays]` | Create an auction-mode slot, starts the first round immediately |
| `/kssa deleterental <slotId>` | Delete a slot. Active renter gets full mailbox refund |
| `/kssa listrentals` | List all rental slots in your current world (chat output with UUIDs) |
| `/kssa forceexpirerental <slotId>` | Run the normal expiry path immediately. Auction slots auto-rearm if `onEmptyAuction = RESTART` |

---

## 6. UI Surfaces

### `ShopBrowsePage` (customer view)
- 9x5 native ItemGrid (45 slots)
- BUY / SELL mode toggle if both are configured for the shop
- Items render at the **exact slot position** the owner placed them in the editor
- Tooltip per slot: item name, price (gold), stock (color-graded by quantity), click hint
- Click confirms quantity via slider, then commits the transaction

### `ShopEditPage` (owner)
- Drag-and-drop between three grids: shop staging, your inventory, your hotbar
- Sub-tabs: Settings / Pricing / Revenue / History
- **Settings tab:** name, description, category, NPC skin (with username), shop icon picker (24 tiles), nameplate toggle, **directory listing buy/extend** (slider 1..30 days, live cost), pickup-shop button (disruptive, returns a token + mails items)
- **Pricing tab:** select a slot in the staging grid -> edit buy/sell price, daily quota, stock cap, mode (Buy / Sell / Both)
- **Revenue tab:** total revenue, tax paid, mailbox status
- **History tab:** transaction log paginated

For **rental-backed shops**, the listing block shows `Rental: 6d 22h remaining` (read from the slot's source-of-truth `rented_until`, not the shop mirror).

### `ShopDirectoryPage`
- Card grid (8 per page), pagination, search
- Filters: tabs (All / Player / Admin / Featured), category dropdown, rating filter, sort (Rating / Sales / Recent)
- Card content: avatar icon, shop name, owner name, rating (stars + numeric), item count, distance from player, listing-remaining-time, status badge (OPEN / CLOSED / FEATURED), category tag
- Click card -> opens browse view of that shop
- Item-search mode: 9x5 grid showing matching items across all shops, with shop name + owner + price in the tooltip

### `ShopAdminPage`
Tabs: Shops, Players, Transactions, Rentals.

**Rentals tab:**
- Search field, 4 filter buttons (ALL / VACANT / AUCTION / RENTED)
- 8 rows per page with pagination
- Per row: slot name, mode badge, state, renter name + remaining, price/day or current bid
- Actions per row: FORCE EXPIRE, DELETE SLOT

### `RentalRentConfirmPage` (fixed-price)
Opens when a player F-keys a vacant FIXED-mode slot.
- Day slider (1..maxDays)
- Live total: `days * pricePerDay`
- Active rentals counter: `X / Y` (Y from `ks.rental.limit.N` or config default)
- Rent + Cancel buttons

### `RentalBidPage` (auction)
Opens when a player F-keys a vacant AUCTION-mode slot.
- Live countdown (refreshes every 2s)
- Current high bid + bidder name
- Your status: high bidder / outbid / no bid placed
- **Rental Period:** `7 day(s) if you win` (from `slot.maxDays`)
- Bid history (last 6, scrollable)
- Bid slider (min = `currentBid + bidIncrement`, max = `max(min*10, 10000)`)
- Place Bid + Close

When auction is between rounds (`auction_ends_at == 0`), countdown reads `Auction not active`.

### `MyRentalsPage`
Self-service for players who hold rentals.
- 6 rows max
- Per row: slot name, expires-in, revenue, item count
- Actions: OPEN EDITOR, EXTEND (re-uses RentConfirmPage with extend mode), RELEASE EARLY

### `RentalStationPage`
Multi-slot browse for the current world.
- Tabs: ALL / VACANT / AUCTION / RENTED
- Slot cards with countdown, action button
- Click -> opens the matching rent/bid page

---

## 7. Rental System

### Concept

Admins place leasable shop slots at fixed positions. Two modes:
- **FIXED** - players pay `pricePerDay * days` for exclusive use
- **AUCTION** - timed bidding round; winner gets the slot for `maxDays`

### Lifecycle

```
                 ┌─────────────────┐
                 │ /kssa createrental│
                 │ -> Vacant Shell │
                 └────────┬────────┘
                          │ player F-keys + rents
                          ▼
                 ┌─────────────────┐
                 │ Rented (PLAYER) │
                 └────────┬────────┘
                          │ rented_until elapsed
                          │ OR /kssa forceexpirerental
                          │ OR /ksshop releaserental
                          ▼
       ┌──────────────────────────────┐
       │ expireSlot                    │
       │  - mail items + balance       │
       │  - despawn renter NPC         │
       │  - delete renter shop row     │
       │  - clear slot runtime state   │
       │  - re-arm auction if mode=AUC │
       │  - recreate Vacant Shell      │
       │  - spawn Vacant Shell NPC     │
       └──────────────┬────────────────┘
                      │
                 (back to top)
```

### Vacant Shell Shop

A "shell" `ShopData` row of `type=ADMIN` with `rentalSlotId` set, `open=false`, `listed_until=0`. Functions as a placeholder NPC - F-keying it opens the rent/bid page instead of the normal browse.

### Auction Flow

1. Slot created with `auction_ends_at = now + durationMinutes * 60_000`.
2. Players F-key, see the bid page, place bids. **Money is not withdrawn at bid time.**
3. Outbid players get a chat notification.
4. If a bid arrives in the last `auctionAntiSnipingSeconds`, the auction extends by that many seconds.
5. If a bid arrives in the last `auctionEndingSoonSeconds`, a one-shot world-broadcast warns "Auction X ends in mm:ss".
6. At `auction_ends_at`:
   - **Has winner:** withdraw `currentHighBid` from winner. On success, start a rental (`days = maxDays`), fire `RentalAuctionWonEvent`. Winner gets chat: `You won 'X'! The shop is yours for the next 7d 0h.`. On insufficient funds, auction restarts with no charge to anyone.
   - **No bids:** `handleEmptyAuction` runs the `onEmptyAuction` action (RESTART / VACANT / DELETE).
7. After the rental window expires (rental rolled into a normal `expireSlot`), the auction auto-rearms if `onEmptyAuction = RESTART`.

### Concurrency

`RentalService` uses per-slot `synchronized` locks (`Map<UUID, Object>`) so two players can't race to rent the same vacant slot. First through the lock wins; the second gets the "already rented" error.

---

## 8. Directory & Listings

### Visibility

A player shop appears in `/ksshop browse` only if `isListedInDirectory()`:
- `listed_until == Long.MAX_VALUE` (forever) -> always listed
- `listed_until > now` -> listed
- otherwise -> hidden

Admin shops bypass this filter - always listed.

### Listing on shop create

| Condition | `listed_until` set to |
|---|---|
| `listingEnabled = false` | `Long.MAX_VALUE` (legacy) |
| Owner has `ks.shop.list.permanent` (explicit, not wildcard) | `Long.MAX_VALUE` |
| First shop ever for this owner AND `listingFreeDaysOnCreate > 0` | `now + freeDays * 86400000` (flag set in `shop_player_flags`) |
| Otherwise | `0` (must `/ksshop list <days>` to surface) |

The "free days on create" is one-time per player lifetime - delete + recreate cannot reuse it.

### Buying / extending a listing

Via chat: `/ksshop list <days>` near the target shop.
Via UI: Shop editor -> Settings -> Directory Listing block (slider + live cost + button).

Cost: `listingPricePerDay * days`. Free with `ks.shop.list.free`. Day count clamped to `[listingMinDays, listingMaxDays]`.

Rental-backed shops: when `rentalShopsListFree = true`, `listed_until` mirrors `rented_until` automatically. The listing is included in the rental price.

---

## 9. NPC System

### Spawn timing

- **First-player-in-world:** all shop NPCs in that world are scheduled for spawn after `npc.spawnDelaySecondsOnJoin` seconds (default 3). Gives the client cosmetic subsystem time to initialise.
- **In-session create / rent / force-expire:** spawned immediately at the slot position via `spawnNpcAtPosition`.
- **Server restart:** on first-player-join, lazy-spawn pass picks up all persisted shops.

### Skin resolution

Order of precedence:
1. `shop.npcSkinUsername` if set explicitly
2. Owner's username (player shops, only when `playerShops.allowNpcCustomization = true`)
3. `npc.defaultSkinUsername` (admin shops + fallback)
4. None -> NPC keeps default model

Skin fetch goes through `playerdb.co/api/player/hytale/<username>` with a 30-min in-memory + on-disk cache (`skin-cache.json`).

If `fetchSkin` returns null (network hiccup, unknown name), retries once after `npc.skinRetryDelaySeconds`.

### Nameplate

| State | Nameplate text |
|---|---|
| Player shop, open | `<ShopName>` |
| Player shop, closed | `<ShopName> [CLOSED]` |
| Player shop, rental-backed (rented) | `<ShopName> (6d 22h left)` |
| Vacant FIXED rental shell | `<DisplayName> [100g/day]` |
| Vacant AUCTION rental shell | `<DisplayName> [AUCTION 500g]` |
| Admin shop | `<ShopName>` |

Remaining time is read from `RentalSlotData.rentedUntil` (source of truth) at spawn / `refreshNameTag` time. Not live-tickling - good enough for multi-day rentals.

### Anti-duplicate

- `PersistentModel` keeps NPCs alive across chunk reloads, but without their skin component. Periodic orphan sweep (every 30s) removes any `Shop_Keeper_Role` NPC not in our tracking map, so we don't accumulate naked duplicates.
- On force-respawn (`/kssa respawnnpcs`), all tracked NPCs in the world are despawned + re-spawned with a single sync sweep afterwards.

---

## 10. Database Schema

All tables are created with `IF NOT EXISTS` on plugin init. Both SQLite + MySQL dialects are shipped.

| Table | Purpose |
|---|---|
| `shop_shops` | Shops (player + admin). Mirrors rental fields (`rental_slot_id`, `rental_expires_at`) for fast joins. Authoritative columns: `id`, `name`, `type`, `owner_uuid`, position, `listed_until`, `featured_until`, `open`, `packed`, `npc_*`, `category`, `tags`, rating + revenue counters |
| `shop_items` | Per-shop item config: itemId, prices, stock, slot index, category, daily limits, BSON metadata |
| `shop_transactions` | Buy/sell history per shop |
| `shop_ratings` | Per-rater ratings (raterUuid + shopId composite key) |
| `shop_notifications` | Stored notifications per player UUID |
| `shop_mailbox` | Pending item + money mails (used by rental refund + offline-sale earnings) |
| `shop_rental_slots` | Rental slot config + runtime: position, mode, max_days, price_per_day or auction params, rented_by, rented_until, auction_ends_at, current high bidder, ending-soon broadcast flag |
| `shop_rental_bids` | Bid history (slot_id, bidder, amount, timestamp). Indexed by (slot_id, timestamp DESC) |
| `shop_player_flags` | Per-player flags. Currently: `free_listing_used` (BOOLEAN). Persists across shop delete |

### Source-of-truth columns vs mirrors

| Concern | Authoritative | Mirror |
|---|---|---|
| Rental expiry | `shop_rental_slots.rented_until` | `shop_shops.rental_expires_at` |
| Listing window | `shop_shops.listed_until` | (none) |

For DB hand-edits while testing: stop server first, edit only the authoritative column, start server.

---

## 11. Events / Integration API

The plugin fires these events on its own `ShopEventBus.getInstance()`. Other mods can subscribe.

| Event | Fires when |
|---|---|
| `ShopCreateEvent` | New shop persisted (player or admin) |
| `ShopDeleteEvent` | Shop removed (with reason) |
| `ShopBuyEvent` | Buy transaction completes |
| `ShopSellEvent` | Sell transaction completes |
| `RentalStartedEvent` | Slot rented (fixed) or auction won + payment cleared |
| `RentalExpiredEvent` | Slot freed (rental window elapsed, force-expire, release-early). Carries reason enum |
| `RentalBidPlacedEvent` | A bid lands on an auction |
| `RentalAuctionWonEvent` | Auction finalised with a paying winner |

### Economy bridge

`ShopEconomyBridge` is reflection-based. It tries:
1. `com.kyuubisoft.core.economy.ExternalEconomyBridge` (if Core mod is loaded)
2. `net.cfh.vault.VaultUnlocked.economyObj()` (direct VaultUnlocked)

If neither resolves, the bridge stays in `NONE` mode and shop transactions are disabled. The plugin retries detection 4 times during early boot (+2s, +5s, +10s, +20s) so providers that register slowly (Ecotale registers after Shops loads) still get picked up. Look for `Economy provider attached on retry` in the log.

### MailboxService integration

`MailboxService.createItemMail(...)` and `createMoneyMail(...)` are used by:
- Rental expiry (items + balance to renter)
- Force-deleted player shops (items + balance to owner)
- Offline sales (transaction earnings stored in mailbox)

---

## 12. Troubleshooting

### `No economy provider detected`
Provider mod loaded after Shops. Wait up to 20s for the retry, then check log for `Economy provider attached on retry`. If still missing, check that VaultUnlocked is installed and your provider (e.g. Ecotale) registers with it.

### `Could not find document Pages/...ui for Custom UI Append command`
A Hytale silent-parse-failure: one .ui file in the load queue has a syntax error, the loader aborts, the error message names whatever file came next. Check the most recently changed `.ui` file for invalid LayoutMode / Anchor / Alignment values.

Valid LayoutModes: `Top / Left / Center / CenterMiddle / TopScrolling / LeftCenterWrap`. NOT valid: `LeftCenter`, `Right`, `Bottom`, etc.

### `Failed to decode ... Unexpected character: 32, '2' expected '"'!`
A `SliderNumberField` event was received but the `EventData` codec declared the field as `STRING`. Sliders send INTEGER. Fix the codec entry.

### Shops drift 1 block after server restart
Was a known bug from the Shop_Block era - the lazy-spawn path used `calculateNpcPosition` which applied a 1.2-block offset along the rotation. Already removed in current versions; if it recurs, check `spawnNpcInternal` is not calling `calculateNpcPosition` again.

### Force-expire leaves no NPC at the slot
Should auto-respawn the vacant shell NPC. If not, run `/kssa respawnnpcs` once to force a full pass.

### Listing shows "permanent" on every new shop
Account is OP / has `ks.shop.*` wildcard, which trips the wildcard sentinel and falls back to default. To grant `ks.shop.list.permanent` on an OP, deny the wildcard for `ks.shop.*` first.

### Player gets "no permission" on `/ksshop create`
LuckPerms ignores the plugin's `default = true` argument. Grant the perm explicitly:
```
lp group default permission set ks.shop.user.create true
```

### "Wrong number of required arguments" on `/kssa createrental`
Hytale validates arg count against the declared list before execute runs. Optional args must be declared via `withOptionalArg` to be accepted positionally.

### Auction stays "not active" after a rental ends
Check `rentalStations.onEmptyAuction`. If `VACANT` or `DELETE`, the auction is not auto-rearmed. Set to `RESTART` (default) or manually re-arm by deleting + recreating the slot.

### DB-edit doesn't take effect
The plugin caches `ShopData` and `RentalSlotData` in memory. Live DB-edits don't sync back to memory, and the next auto-save (every 60s) overwrites your edit with the cache value. Always: stop server first, edit DB, start server.

For listings / rental expiry: edit only the authoritative column (see [section 10](#10-database-schema)). The mirror columns (`shop_shops.rental_expires_at`) are read for some fast-paths but the source-of-truth is in `shop_rental_slots.rented_until`.

---

## Changelog

See `docs/changelogs/shops/CHANGELOG.md` for the running per-fix history. Major themes:

- Spawn timing (Spawn-Delay, Skin retry, Lazy-spawn offset removal)
- Permissions (Wildcard sentinel, `hasExplicit` for listing perks)
- Rental system (Vacant shell NPC lifecycle, force-expire, auto-rearm after rental, Bid page rental period)
- UI fixes (LayoutMode parse pitfall, Slider INTEGER codec, BankSlot.png bundling)
- Command argument migration (Optional args via `withOptionalArg`)
- Listing system (one-time freebie, in-editor purchase block, directory remaining time)
- Transfer (request/accept handshake, dedicated subcommands)
- Economy bridge (retry on slow provider registration)
