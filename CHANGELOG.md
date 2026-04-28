# Shops Plugin - Changelog

## [Browse Grid - Preserve Editor Slot Positions] - 2026-04-28

### Feature

Items in the customer Browse view now sit at the **exact same slot** the owner placed them in the editor. Previously the browse view compacted everything left-to-right, so an item dropped in slot 30 by the owner appeared in slot 0 for buyers - cosmetic-shop layouts (themed columns, item categories by row, etc.) didn't survive.

### Behaviour

- Owner places `Iron_Sword` in editor slot 10 -> buyer sees `Iron_Sword` in browse slot 10
- Owner places single item in slot 30 -> buyer sees 30 empty slots then the item
- Empty slots between items render as inactive ItemGridSlots (clickable but no-op)
- Pagination is now slot-driven: `totalPages = (highestSlot / 45) + 1`. With the default 45-slot cap (= 1 page) nothing changes; a higher `maxItemsPerShop` would allow shops to span multiple pages with their layout preserved per page.

### Click Mapping

`handleBuyClick` / `handleSellClick` now resolve the clicked item by its configured `slot` field, not by list index. Linear scan over `buyItems` / `sellItems` (max 45 entries each) - the cost is negligible and avoids needing a slot-indexed lookup map.

### Files Changed
- `ui/ShopBrowsePage.java` - `populateBrowseGrid` placement loop indexes by configured slot, click handlers resolve via new `findItemBySlot` helper

---

## [Auction Auto-Restart After Rental Expiry] - 2026-04-28

### Problem

After winning an auction and the rental window expiring, the AUCTION slot stayed idle - bid page showed "Auction not active" forever, no new round started. Admins had to manually re-arm via DB or some workaround. Looked broken.

### Root Cause

`expireSlot` cleared the slot's runtime state (`rentedBy`, `rentedUntil`, `auctionEndsAt` all reset) and recreated the vacant shell, but didn't kick off a new auction round for AUCTION-mode slots. The `onEmptyAuction = "RESTART"` config option only governed the **no-bids** path (`handleEmptyAuction` -> called when an auction closes with zero bids). The post-rental-expiry path was missed.

The Javadoc even said "For AUCTION slots the finalizeAuction caller may start a new auction round right after" - true for empty-auction restart, but the rental-expiry caller (`scanExpired` rental-window branch) never did.

### Fix

`expireSlot` now mirrors the `RESTART` behaviour for AUCTION slots after clearing state:

```java
if (slot.getMode() == AUCTION) {
    String onEmpty = config.rentalStations.onEmptyAuction;
    if ("RESTART".equalsIgnoreCase(onEmpty) || onEmpty == null) {
        long newEnd = now + slot.getAuctionDurationMinutes() * 60_000L;
        slot.setAuctionEndsAt(newEnd);
    }
}
```

A fresh auction round starts immediately. The bid page now reads `Time Remaining: 1h 0m` instead of `Auction not active`. If `onEmptyAuction = "VACANT"` or `"DELETE"` is configured, the rental-expiry path respects that the same way the no-bids path already did.

Logs `Auction '<slot>' re-armed after rental expiry, new end: <ms>` on success.

### Files Changed
- `rental/RentalService.java` - `expireSlot` re-arms the auction round for AUCTION-mode slots when `onEmptyAuction = RESTART`

---

## [Bid Page - Surface Rental Period + Better Auction-Inactive State] - 2026-04-28

### Problem

Two bidder UX gaps on `RentalBidPage`:

1. **Rental period invisible**: bidders had no way to see how long the slot would be theirs after winning. They saw the auction countdown and the high bid, but not "X days rental".
2. **Countdown stuck on `0s`**: when the auction was between rounds (just finalised, about to be auto-restarted, or admin re-arming) `auction_ends_at` is `0`. The page rendered `formatRemainingTime(0) -> "0s"` which read as a frozen / broken UI.

### Fix

1. New "Rental Period" row in the bid page UI (under "Your Status"), displays `slot.getMaxDays() + " day(s) if you win"` in soft green. Bidders now know exactly what they're competing for.
2. Countdown text adapts to auction state:
   - `auction_ends_at == 0` -> `"Auction not active"` (orange)
   - `auction_ends_at <= now` -> `"Closing..."` (the scheduler tick will finalise within the next 60s)
   - `auction_ends_at > now` -> `formatRemainingTime(...)` as before

The 2-second refresh tick keeps both lines live. So as the auction restarts after a win, the bid page transitions: `Closing... -> Auction not active -> 1h 0m`.

### Files Changed
- `resources/Common/UI/Custom/Pages/Shop/RentalBid.ui` - new `#RentalPeriodValue` row between Status and Bid History
- `rental/ui/RentalBidPage.java` - countdown branches on `auctionEndsAt` value, `#RentalPeriodValue` populated from `slot.getMaxDays()`

---

## [Rental Remaining Time - Read From Slot, Not Shop Mirror] - 2026-04-28

### Problem

After editing `shop_rental_slots.rented_until` directly in the DB to short-circuit a rental for testing, the in-game UI still showed the original value (e.g. 60 days left) instead of the new edited expiry. The user complained that the displays "seem to take days from `max_days` instead of `now - rented_until`" - the math actually came out the same as `max_days * 86400000` because the original `rentedUntil = now + max_days * 86400000`.

### Root Cause

Two columns hold the rental expiry:

| Column | Table | Role |
|---|---|---|
| `rented_until` | `shop_rental_slots` | Source of truth - what `RentalExpiryTask` reads |
| `rental_expires_at` | `shop_shops` | Denormalised mirror set when the renter shop was created |

The Nameplate (`ShopNpcManager.applyNameTag`) and the Editor settings panel (`ShopEditPage`) both read from the **mirror** (`shop.rentalExpiresAt`). A direct DB edit on the slot's `rented_until` column does not propagate to the mirror, so the UI lagged.

### Fix

Both displays now read from `RentalSlotData.rentedUntil` (the slot's source-of-truth) via a `RentalService.getSlot(rentalSlotId)` lookup, and only fall back to `shop.rentalExpiresAt` when the slot isn't resolvable (defensive). Hand-editing the slot row in DB is now reflected in the UI immediately on the next render tick.

### Files Changed
- `npc/ShopNpcManager.java` - `applyNameTag` reads `slot.rentedUntil` for rental-backed PLAYER shops
- `ui/ShopEditPage.java` - `buildSettingsPanel` rental-status branch reads `slot.rentedUntil`

---

## [Rental Remaining Time - Surfaced In Three Places] - 2026-04-28

### Feature

After winning an auction (or renting a fixed slot), the player now sees the remaining rental window in three places, so "how long is this shop mine?" is answered without digging.

### Where it shows up

**1. Auction-win chat message**

Was: `You won the auction for 'X'! Rental active until 7d 0h.` (confusing - "until" reads like a deadline date but `7d 0h` is a duration)

Now: `You won the auction for 'X'! The shop is yours for the next 7d 0h. Use /ksshop edit to stock it.`

**2. NPC nameplate above the rented shop**

Player-shop NPCs that back a rental slot now append a remaining-time suffix to the nameplate:

```
MyAwesomeShop (6d 22h left)
```

For sub-day windows: `(15h left)`. Vacant rental shells keep their existing `[100g/day]` / `[AUCTION 500g]` suffixes (they encode state differently). Standard non-rental shops are unchanged.

Note: nameplate is set on spawn / refreshNameTag, not live - so the `(Xd Yh)` is the value at NPC spawn time. Good enough for a multi-day window. If you want it live-ticking later we can add a periodic nameplate refresh.

**3. Shop Editor -> Settings -> Directory Listing block**

For rental-backed shops the status line now reads:

```
Rental: 6d 22h 14m remaining
```

instead of the listing-window status. Reason: when `rentalShopsListFree=true` (default) the listing window equals the rental window - showing "Listed for 6d 22h" is duplicate info, and "Rental remaining" is the more meaningful framing for the renter. Updates every time the page is opened / refreshed, so it tracks live (down to the minute).

Non-rental shops keep the existing listing-window status text.

### Files Changed
- `defaults/localization/en-US.json` - reworded `shop.rental.auction_won`, version bumped 27 -> 28
- `i18n/ShopI18n.java` - `CURRENT_LOCALIZATION_VERSION`
- `npc/ShopNpcManager.java` - `applyNameTag` appends rental remaining-time suffix for rental-backed PLAYER shops
- `ui/ShopEditPage.java` - settings-panel listing status switches to rental-remaining line for rental-backed shops

---

## [NPC Lazy-Spawn - Drop Legacy 1.2-Block Offset] - 2026-04-28

### Problem

After force-expiring (or naturally expiring) a rental, the new vacant-shell NPC spawned about **one block back-left** of the original slot position. Same effect happened after every server restart for any NPC: positions drifted by ~1 block.

### Root Cause

There were two NPC spawn paths with **inconsistent position math**:

| Path | Method | Position used |
|---|---|---|
| Live spawn (`/kssa createrental`, `rentSlot`, `forceexpirerental`, ...) | `spawnNpcAtPosition` -> `spawnNpcInternalAt` | `shop.posX/Y/Z` directly |
| Lazy spawn on first-player-in-world (server restart, chunk load) | `onPlayerAddedToWorld` -> `spawnNpcInternal` | `calculateNpcPosition(shop)` |

`calculateNpcPosition` applied a `1.2 * sin/cos(rotY)` offset along the NPC's facing direction. At the test slot's rotation (1.095 rad ≈ 63°) that worked out to `(-1.07, 0, +0.55)` - "1 block back-left" exactly.

The offset was a leftover from the `Shop_Block` era, when shops were anchored to a placed block and the NPC stood "1.2 blocks behind" that block. After the migration to standalone NPCs, the shop's stored coords already **are** the NPC coords - the offset was just drift.

So the live path placed NPCs at the right spot, but the next world-load shifted them. Every restart drifted again until you'd see the new vacant shell respawn back at the correct slot pos and the position would suddenly "jump" one block.

### Fix

`spawnNpcInternal` (lazy-spawn) now uses `shop.getPosX/Y/Z` directly instead of `calculateNpcPosition(shop)`. Both spawn paths produce identical positions. `calculateNpcPosition` and `NPC_OFFSET_DISTANCE` are kept on the class for now in case any legacy block-anchored shop still relies on them, but no path calls into it anymore.

### Files Changed
- `npc/ShopNpcManager.java` - `spawnNpcInternal` no longer applies the 1.2-block back-offset

---

## [Vacant Shell After Expiry - Missing NPC Respawn] - 2026-04-28

### Problem

Force-expiring (or naturally expiring) a rental:

```
[KyuubiSoft Shops] Deleted shop 'TestRentFixed1' (74ab53b9-...)
[KyuubiSoft Shops] Created shop 'TestRentFixed1 [100g/day]' (aa459ef2-...)
[KyuubiSoft Shops] Rental expired (FORCE_EXPIRED): 'TestRentFixed1' - 0 items + 0.0g mailed to KyuubiDDragon
```

DB was correct: old renter shop deleted, new vacant shell row created with `rentalSlotId` set, mail dispatched. But **no NPC** appeared at the slot position. The shell row sat invisible in the DB until the next server restart, leaving the slot effectively unrentable for the rest of the session.

### Root Cause

`createVacantShellShop` only persisted the `ShopData` row via `shopManager.createShop(shell)`. The initial `/kssa createrental` flow worked because the command itself ran a follow-up `spawnNpcAtPosition` after the slot was created. The expiry path (`expireSlot` -> `createVacantShellShop`) had no follow-up spawn - so the only path that produced a visible NPC was the original create command.

The Javadoc on `createVacantShellShop` even said "the existing NPC spawn pipeline then treats it like any other admin shop and spawns ... on world load" - which is true but only happens at the **next** world load, not live.

### Fix

`createVacantShellShop` now spawns the NPC inline:

1. Persist the shell `ShopData`
2. `npcManager.registerShopInWorld(shell)`
3. Resolve default world via `Universe.get().getDefaultWorld()` (no Player reference needed - works from the scheduler thread)
4. `world.execute(() -> spawnNpcAtPosition(shell, world, pos, rotY))`

If the world cannot be resolved (server still booting, etc), the spawn is deferred to the next world-init pass via the existing `onPlayerAddedToWorld` lazy-spawn path.

`spawnNpcAtPosition` is idempotent (despawns any tracked NPC for the same shopId before spawning) - so the redundant follow-up spawn in `/kssa createrental` still works without double-spawning.

### Files Changed
- `rental/RentalService.java` - `createVacantShellShop` now triggers a live NPC spawn; new `resolveDefaultWorld()` helper for scheduler-thread paths

---

## [Listing Slider - Wrong Codec Crashed Page With "Loading..."] - 2026-04-28

### Problem

Moving the new "Days" slider on the Shop Editor's Settings -> Directory Listing block caused a "Loading..." overlay in the centre of the screen and froze the page. Server log:

```
SEVERE [World|flat_world] Failed to run task!
com.hypixel.hytale.codec.exception.CodecException: Failed to decode
  ...
Caused by: java.io.IOException: Unexpected character: 32, '2' expected '"'!
  at StringCodec.decodeJson(StringCodec.java:31)
```

### Root Cause

`EditData` declared `@ListingDays` with `Codec.STRING` and `String listingDaysVal`. `SliderNumberField` sends a raw integer (`2`) over the wire, not a quoted string (`"2"`). The page's `BuilderCodec` tried to read the integer as a string, hit a non-quote character at position 0, and threw - which freezes the whole interactive page (`InteractiveCustomUIPage.handleDataEvent` aborts before any state can be written back).

The other Hytale slider in the codebase (`RentalRentConfirmPage` "Days" slider) uses `Codec.INTEGER` + `Integer daysValue`. The new listing slider should match.

### Fix

```java
// Was:
.addField(new KeyedCodec<>("@ListingDays", Codec.STRING),
    (data, value) -> data.listingDaysVal = value, ...)
private String listingDaysVal;

// Now:
.addField(new KeyedCodec<>("@ListingDays", Codec.INTEGER),
    (data, value) -> data.listingDaysVal = value, ...)
private Integer listingDaysVal;
```

The `case "listingDays"` handler in `handleDataEvent` lost the `Integer.parseInt` + `trim()` dance; it now uses `data.listingDaysVal` directly with a 1..30 clamp.

### Files Changed
- `ui/ShopEditPage.java` - codec entry switched STRING -> INTEGER, field type String -> Integer, handler simplified

---

## [Vacant Rental NPC - F-Key Was Swallowed] - 2026-04-28

### Problem

Pressing F on a vacant rental NPC did nothing. No rent dialog, no chat message, no error.

Root cause: there are two independent F-key code paths.

1. `ShopBlockInteraction.onPlayerInteract` (block + entity interact event) - already routed vacant rental shells to `RentalRentConfirmPage` / `RentalBidPage`.
2. `ActionShopInteract.execute` (NPC role action driven by Citizens / NPC behaviour tree) - did **not** know about rental shells.

When a closed-shop check was added to `ActionShopInteract` (see `[NPC F-Key - Closed Shop Bypass Fix]`), it began rejecting any F-key on a shop with `open=false`. Vacant rental shells are stored as `ADMIN`-type with `open=false` exactly so they don't show up as buyable shops - so the closed-check matched them and silently bailed before any rental routing could fire.

### Fix

Added the same vacant-rental-shell detection to `ActionShopInteract.execute`, **before** the closed-shop check. Mirrors the `ShopBlockInteraction.onPlayerInteract` block flow:

```java
if (shop.getRentalSlotId() != null && shop.isAdminShop()) {
    RentalSlotData slot = rentalService.getSlot(shop.getRentalSlotId());
    if (slot != null && slot.getRentedBy() == null) {
        if (slot.getMode() == AUCTION) open RentalBidPage;
        else open RentalRentConfirmPage;
        return;
    }
}
```

Now both interaction entry points (block-interact + NPC-action) handle vacant shells the same way.

### Files Changed
- `npc/ActionShopInteract.java` - added rental-shell branch in front of the closed-shop check; uses fully-qualified class names to keep the import block from mushrooming

---

## [Directory Cards - Show Listing Remaining Time] - 2026-04-27

### Feature

Each card in the shop browser now shows how long that shop's directory listing has left, so players can pick the recently-bought spots from the about-to-expire ones at a glance.

### What it looks like

Existing items-line on each card was only showing `5 items`. Now appends the listing state:

| Shop state | Items line reads |
|---|---|
| Player shop, 5 days listed | `5 items  -  Listed 5d 0h` |
| Player shop, < 1 day | `5 items  -  Listed 14h` |
| Player shop, permanent (`ks.shop.list.permanent`) | `5 items  -  Listed permanent` |
| Admin shop | `5 items` (no suffix - admin shops are always listed and the suffix would be noise) |
| Player shop with `listedUntil = 0` | n/a - shouldn't appear in directory anyway |

Used the same row-label that already showed `5 items` to avoid touching the .ui layout (8 cards, would have been a heavy rewrite for a single line). Distance still shows on the right.

### Files Changed
- `ui/ShopDirectoryPage.java` - new `formatListingSuffix(shop, playerRef, i18n)` helper, items-line composition extended in the card-render loop
- `defaults/localization/en-US.json` - added `shop.directory.listed_for` and `shop.directory.listed_permanent`, version bumped 26 -> 27
- `i18n/ShopI18n.java` - bumped `CURRENT_LOCALIZATION_VERSION`

---

## [Wildcard Permission Defence - Listing + Limits] - 2026-04-27

### Problem

Every newly created shop displayed `Status: listed permanently`, regardless of how `listingFreeDaysOnCreate` was configured. Same root cause as the earlier `ks.shop.limit.shops.N` issue: when the testing account is OP / has a LuckPerms wildcard like `*` or `ks.*`, **every** `hasPermission(...)` query returns `true`. So:

- `player.hasPermission("ks.shop.list.permanent", false)` -> true -> every shop becomes `listedUntil = Long.MAX_VALUE`
- `player.hasPermission("ks.shop.list.free", false)` -> true -> every paid listing becomes free
- `player.hasPermission("ks.shop.limit.shops.200", false)` -> true -> max-shops cap effectively disabled

OP behaviour for admin nodes (`ks.shop.admin`) is fine and intended, but the listing/limit nodes are gameplay-economy gates - they must require an explicit grant, not match wildcards.

### Fix

Added a wildcard sentinel probe in `PermissionLimits`:

```java
// A node no real permission setup would ever grant. If the backend
// says "yes" to this, we know the player has a wildcard match.
private static final String WILDCARD_SENTINEL =
    "ks.shop.__wildcard_probe.sentinel_check_zzz";

public static boolean hasWildcard(Player player) { ... }
public static boolean hasExplicit(Player player, String node) {
    if (hasWildcard(player)) return false;
    return player.hasPermission(node, false);
}
```

`hasExplicit` only returns true when the node is granted explicitly (not via wildcard). Threaded through both ShopService listing paths plus the existing numeric `resolve()` scan.

### Behaviour Matrix

| Account state | `hasExplicit(...permanent)` | `resolveMaxShops` |
|---|---|---|
| Plain player, no perms | false | global default |
| Player with explicit `ks.shop.list.permanent` | **true** | global default |
| Player with explicit `ks.shop.limit.shops.5` | false | **5** |
| OP / `*` wildcard | false (sentinel hits) | global default |
| OP **with explicit** `ks.shop.list.permanent` on top of wildcard | false (still sentinel-blocked) | global default |

The last row is intentional: there is no clean way to distinguish "wildcard + explicit grant" from "wildcard alone", and we prefer the safe interpretation. To grant an OP a permanent listing today, set the wildcard *off* on `ks.shop.list.*` and re-grant the specific node, or test feature-checks on a non-OP rank.

### Files Changed
- `util/PermissionLimits.java` - `WILDCARD_SENTINEL`, `hasWildcard()`, `hasExplicit()`, wildcard-guard in `resolve()`
- `service/ShopService.java` - both `ks.shop.list.permanent` checks (create flow + purchase flow) and the `ks.shop.list.free` check switched to `hasExplicit`

---

## [ShopEdit.ui - Invalid LayoutMode Crashed Asset Loader] - 2026-04-27

### Problem

Logging in produced

```
Could not find document Pages/Ecotale_BalanceHud.ui for Custom UI Append command
```

The error mentioned Ecotale, but actually had nothing to do with Ecotale. The user observed that it appeared right after the listing-UI block was added to `ShopEdit.ui`. That is the well-known Hytale silent-parse-failure: when one .ui file fails to parse, the loader aborts the rest of the asset queue and the crash report names whatever file was loaded next, not the file with the actual bug.

The actual culprit was the new "Days" slider row:

```
Group {
    Anchor: (Left: 0, Right: 0, Height: 22);
    LayoutMode: LeftCenter;     // <-- not a valid LayoutMode value
    Label { ... }
    ...
}
```

`LayoutMode: LeftCenter` is not in Hytale's enum (only `Top / Left / Center / CenterMiddle / TopScrolling / LeftCenterWrap` etc are valid). The parser silently aborted ShopEdit.ui, then failed every subsequent asset in the queue.

### Fix

- `LayoutMode: LeftCenter` -> `LayoutMode: Left` on the slider-row group
- Slider-row child labels keep their `VerticalAlignment: Center` to give the same visual centering
- Tightened `Anchor: Height` and label heights to match the `RentalRentConfirm.ui` pattern that was used as the original reference

### Followup

Updated `MEMORY.md` `.ui Parse-Pitfalls` section: added LayoutMode valid-value list and a note that the loader's "Could not find document" error names the **next** file in queue, not the broken one - so do not chase the named UI when this fires.

### Files Changed
- `resources/Common/UI/Custom/Pages/Shop/ShopEdit.ui` - fixed `LayoutMode` on the listing slider row, normalised label heights

---

## [Economy Detection - Retry Instead of Hard Dep on Ecotale] - 2026-04-27

### Problem

Earlier we added `Ecotale:Ecotale` as an `OptionalDependency` in the Shops manifest to fix the load-order race that left the economy bridge on `NONE`. That worked for the original symptom but introduced a fresh one: declaring Ecotale as a dependency made Hytale's plugin loader try to resolve Ecotale's asset pack folder (`Server/mods/Ecotale_Ecotale/`). That folder is intended for runtime data only - DB file, config - and ships without a `manifest.json`. Result: every login produced

```
Could not find document Pages/Ecotale_BalanceHud.ui for Custom UI Append command
```

The Shops mod's recent changes were the trigger - reverting the manifest change made the noise go away, confirming Ecotale itself is not at fault here.

### Fix

Two parts:

1. **Manifest:** removed `Ecotale:Ecotale` from `OptionalDependencies`. We do not actually need a hard load-order link to the provider mod - we only need VaultUnlocked, which is already declared.
2. **Bridge retry:** `ShopEconomyBridge` now exposes `retryDetect()` (no-op once a backend is bound) and `ShopPlugin` schedules four retry attempts at +2s / +5s / +10s / +20s after enable. Slow-starting providers (Ecotale registers itself with VaultUnlocked one tick after enable) get picked up on the first retry and the log shows `Economy provider attached on retry`.

This decouples Shops from any specific provider mod while still solving the original race.

### Files Changed
- `src/main/resources/manifest.json` - dropped `Ecotale:Ecotale` optional dep
- `bridge/ShopEconomyBridge.java` - split `detect()` into one-shot init + idempotent `retryDetect()` over a shared `runDetect()` core
- `ShopPlugin.java` - schedule four retry calls during the auto-save scheduler setup

---

## [Directory Listing Inside Shop Editor] - 2026-04-24

### Feature

Players can now buy or extend the directory listing for their shop directly from the **Shop Editor -> Settings** tab. No more memorising `/ksshop list <days>` from chat.

### UI

New "DIRECTORY LISTING" block in the Settings panel of `ShopEdit.ui`, just above the Pickup-Shop button:

- **Status line**: live read of the shop's listing state
  - `Status: listed for 5d 14h`
  - `Status: not listed`
  - `Status: listed permanently` (when `ks.shop.list.permanent` perm or feature disabled)
- **Days slider** (1..30) with the current value rendered next to it
- **Cost line**: `Cost: 500 Gold (100/day)` - recomputed live as the slider moves
- **BUY / EXTEND LISTING button** - green tinted, runs the same `ShopService.purchaseListing` path as the chat command

### Wiring

- `ShopEditPage` got `listingDaysToBuy` state + `handleBuyListing()` handler
- New event-bindings: slider `ValueChanged -> Field "listingDays"`, button `Activating -> Button "buyListing"`
- New codec entry `@ListingDays`
- `buildUI` Settings-panel section now also paints the listing status, slider value, and computed cost
- Service-layer was already exposing `purchaseListing(player, playerRef, shopId, days) -> ListingResult` from the chat-command refactor; nothing changed in the service layer, this is a UI-only addition

### Files Changed
- `resources/Common/UI/Custom/Pages/Shop/ShopEdit.ui` - new `#ListingHeader` / `#ListingStatusLbl` / `#ListingDaysSlider` / `#ListingDaysValue` / `#ListingCostLbl` / `#BuyListingBtn` block
- `ui/ShopEditPage.java` - state field, button case, field case, EventData codec entry + private member, event bindings, settings-panel render block, `handleBuyListing` method

---

## [Free Listing - Once Per Player Lifetime] - 2026-04-24

### Problem

`listingFreeDaysOnCreate` (default 7) was applied **on every new shop** a player created. A player could `/ksshop create A` -> `delete A` -> `/ksshop create B` -> get the 7 free days again. Even with the freshly-introduced max-shops cap, the freebie was reusable: delete a shop, make another, free listing, repeat. The economy sink (paid listings) was effectively bypassable.

### Fix

The freebie is now strictly **one per player, ever**, persisted in a new `shop_player_flags` table:

```sql
shop_player_flags(
  player_uuid VARCHAR(36) PRIMARY KEY,
  free_listing_used BOOLEAN DEFAULT FALSE
)
```

`ShopService.createPlayerShop` queries `database.hasUsedFreeListing(ownerUuid)` before deciding the listing state:

| Condition | listed_until |
|---|---|
| `listingEnabled = false` | `Long.MAX_VALUE` (legacy) |
| owner has `ks.shop.list.permanent` | `Long.MAX_VALUE` |
| `freeDays > 0` AND first shop ever | `now + freeDays*day`, flag set |
| `freeDays > 0` BUT already used | `0` (must `/ksshop list <days>`) |
| `freeDays = 0` | `0` |

The flag survives shop deletion - the player_flags row is independent of `shop_shops`. Logs `Granted N-day free listing to <name> (first shop)` on the first hit so you can verify in tests.

### Files Changed
- `data/ShopDatabase.java` - new `shop_player_flags` table (MySQL + SQLite), `hasUsedFreeListing()` + `markFreeListingUsed()`
- `service/ShopService.java` - listing-bootstrap branch in `createPlayerShop` checks the flag before granting the freebie

---

## [Shop Transfer - Target Acceptance Required + Subcommand Flow] - 2026-04-24

### Problem

Two issues with the transfer command:

1. **Sender confirmed alone, target had no say**: `/ksshop transfer <shop> <player>` followed by `/ksshop transfer <shop> <player> confirm` finalised ownership without the target ever opting in. The target only learned about the new shop afterwards. That is dangerous (someone could transfer a debt-laden shop, a flagged shop, etc.) and surprising (target ends up owning something they did not ask for).
2. **`--confirm=confirm` syntax was forced on users**: Hytale's command parser treats positional optional STRING args as named flags. The chat suggestion line said "type `/ksshop transfer X Y confirm`", but actually typing it failed with `expected:2, actual:3`; users had to type `--confirm=confirm`, which is ugly and undocumented.

### Fix

Reworked the transfer flow into a request/accept handshake using subcommands - this also makes the optional-arg quirk irrelevant.

```
/ksshop transfer <shop> <player>     # sender stages a request
/ksshop accepttransfer               # target opts in
/ksshop declinetransfer              # target rejects
```

Behaviour:
- Sender command stages a `TransferRequest` keyed by **target UUID** (with 60s expiry) and chat-pings both sides.
- Target chat: "X wants to transfer the shop `Y` to you. Type /ksshop accepttransfer or /ksshop declinetransfer within 60 seconds."
- `accepttransfer` validates the request is still pending + the shop's owner has not changed since, then runs `ShopService.transferShop` and refreshes the NPC nameplate. Sender (if online) gets a confirmation chat.
- `declinetransfer` clears the request and notifies the sender.
- Second `/ksshop transfer` against the same target while one is pending is rejected with `target_busy` (no silent overwrite of an active offer).

Sender no longer types confirm. Target accepts via dedicated subcommand. Aligned with how delete-confirm could work too if we want to migrate that next.

### Files Changed
- `commands/ShopCommand.java` - renamed `TransferConfirm` -> `TransferRequest` (keyed by target now), `TransferCmd` reduced to staging, new `AcceptTransferCmd` + `DeclineTransferCmd`
- `i18n/ShopI18n.java` - bumped `CURRENT_LOCALIZATION_VERSION` 25 -> 26
- `defaults/localization/en-US.json` - 8 new `shop.transfer.*` keys (`target_busy`, `request_sent`, `request_received`, `no_pending`, `expired`, `accepted_target`, `accepted_sender`, `declined_target`, `declined_sender`)

---

## [Confirm-Flow Commands - Optional Args Migration] - 2026-04-24

### Problem

`/ksshop transfer TransferTest DeDuckDev confirm` errored with `the wrong number of required argument was specified. expected:2, actual:3`. Same trap that hit the rental-create commands earlier - Hytale validates declared arg count against the user input **before** execute runs, so any trailing token a command tries to recover via `ctx.getInputString().split()` is rejected as "too many arguments".

Three more commands had the same shape:
- `/ksshop transfer <shop> <player> [confirm]` - 2 declared, 3 parsed -> rejects on confirm step
- `/ksshop delete <shop> [confirm]` - 1 declared, 2 parsed -> rejects on confirm step
- `/kssa closeplayer <shopId> [reason...]` - 1 declared, 2 parsed -> rejects with reason
- `/kssa blacklist <action> [itemId]` - 1 declared, 2 parsed -> rejects with itemId

### Fix

All four migrated to the Hytale-idiomatic optional-arg pattern (cached `Argument<?, ?>` handles in the constructor, read via `ctx.provided(arg) ? ctx.get(arg) : default` in execute). Manual `parts[]` splitting dropped from these commands. Confirm tokens are now real, declared optional `STRING` args - exactly matches the chat-suggestion shown to the user.

`/ksshop rate <shopId> <stars>` was checked too: it declares 2, parses 2, no fix needed.

### Files Changed
- `commands/ShopCommand.java` - `TransferCmd` and `DeleteCmd` constructors + execute bodies
- `commands/ShopAdminCommand.java` - `ClosePlayerCmd` and `BlacklistCmd` constructors + execute bodies

---

## [ShopDirectoryPage - Double Pagination Bug] - 2026-04-24

### Problem

The directory header reported "12 shops, 2 pages" correctly, but page 2 was empty. Page 1 showed 8 cards (correct for `CARDS_PER_PAGE = 8`), and clicking "Next" took you to page 2 with the page counter updated but every card hidden.

### Root Cause

Pagination was applied **twice**:

1. The data layer pre-paginated. `rebuildFilteredShops` either calls `directory.searchShopsFiltered(..., currentPage, CARDS_PER_PAGE)` or does `matching.subList(currentPage*8, currentPage*8 + 8)` for the my-shops filter. After this, `filteredShops` held **only the current page** (size 8, or 4 for the trailing page).

2. Then `buildUI` paginated again. The card loop computed `actualIndex = currentPage * CARDS_PER_PAGE + i` and looked up `filteredShops.get(actualIndex)`.

On page 1 (`currentPage = 0`): `actualIndex = 0..7`, `filteredShops.size() = 8` -> works by coincidence.
On page 2 (`currentPage = 1`): `actualIndex = 8..15`, `filteredShops.size() = 4` -> every `actualIndex < size` check failed -> every card hidden.

`handleCardClick` had the same bug, so even if a card had been visible it would have opened the wrong shop.

### Fix

Both call sites now index `filteredShops` directly by the loop / card index. Comment added so the next reader knows the data layer already did the slicing.

```java
// Was:
int actualIndex = currentPage * CARDS_PER_PAGE + cardIndex;
if (actualIndex < filteredShops.size()) ...

// Now:
if (cardIndex < filteredShops.size()) ...
```

### Files Changed
- `ui/ShopDirectoryPage.java` - dropped `currentPage * CARDS_PER_PAGE` re-pagination in `buildUI` card loop and `handleCardClick`

---

## [Sell Flow - Missing Success Message + Reject Logging] - 2026-04-24

### Problem 1 - No chat feedback on successful sale

After selling items to a shop, the player received nothing in chat. The buy flow showed `shop.buy.success` ("Bought {n}x {item} for {price} {currency}"), but the sell branch in `ShopBrowsePage.handleConfirm` only handled the failure case - no message at all on success. Players had no confirmation the transaction went through, and no quick read of how much gold they got.

### Fix 1

`ShopBrowsePage.handleConfirm` now mirrors the buy branch on the sell side: on `sellItem` success it sends `shop.sell.success` ("Sold {n}x {item} for {price} {currency}") in green to the seller. The localisation key already existed in `en-US.json` and the eight other shipped languages - it just was not being called.

### Problem 2 - Sell rejections invisible without fine-level logging

`sellItem` had ~10 distinct rejection reasons (closed shop, self-sell, sell disabled, sell price 0, max stock, buyback quota, shop balance, inventory miss) but every single one was logged at `LOGGER.fine`, which is filtered out at the default INFO level. Server admins had no way to debug a `Sale failed` complaint without enabling fine-level globally.

### Fix 2

Promoted every "Sell rejected: ..." log line in `sellItem` from `fine` to `info`. Three of them now also include the actor name, the actual amounts, or a remediation hint:
- `Sell rejected: seller <Name> is the shop owner`
- `Sell rejected: shop '<Name>' balance insufficient (X < Y) - owner needs to /ksshop deposit`
- `Sell rejected: failed to remove Nx <itemId> from seller inventory (not enough in inventory?)`

### Files Changed
- `ui/ShopBrowsePage.java` - sell branch sends `shop.sell.success` on success
- `service/ShopService.java` - reject logs upgraded `fine` -> `info` with actionable detail

---

## [NPC F-Key - Closed Shop Bypass Fix + Deploy Lock Workaround] - 2026-04-24

### Problem 1 - Non-owners could still browse closed shops

`/ksshop close` correctly flipped `shop.isOpen() -> false` and the nameplate got the `[CLOSED]` suffix, but a second player (non-owner) could still F-key the NPC and see the full browse UI. The closed-block in `ShopBlockInteraction.handleInteraction` never ran because NPC F-key presses flow through a different entry point: `ActionShopInteract.execute()` (the NPC action wired via `Shop_Keeper_Role.json`'s `HasInteracted`). That handler opened the browse page for non-owners without any open/closed check.

### Fix 1

Added the same `!shop.isOpen() && !isOwner -> shop.error.closed` early return inside `ActionShopInteract.execute()`'s world-thread block. Owners still bypass the check (they can manage the editor while closed); non-owners get the chat message and the browse UI never opens.

### Problem 2 - deployToServer failed on live server

After re-pointing `deployToServer` at the live server's mods folder, Gradle's incremental-build state tracking tried to MD5-hash every sibling file in the target dir - including Ecotale's running H2 DB (`Ecotale_Ecotale/ecotale.mv.db`), which is locked while the server is up. The task failed with `Cannot access a file in the destination directory`.

### Fix 2

Added `doNotTrackState("Copies into a live server mods folder with other mods' locked files")` to the `deployToServer` task. Deploy now always runs and ignores sibling file locks. Trade-off: the task can't skip when nothing changed, but it is a one-file copy so cost is negligible.

### Files Changed
- `npc/ActionShopInteract.java` - added closed-check for non-owners + `Message` import
- `mods/shops/build.gradle.kts` - `deployToServer.doNotTrackState(...)`

---

## [Owner F-Key - Open Real Editor Instead of Browse Placeholder] - 2026-04-24

### Problem

F-keying your own shop NPC (owner F-Key flow) opened the read-only `ShopBrowsePage` instead of the editor. The code had a TODO placeholder from before `ShopEditPage` existed - never updated once the editor was implemented:

```java
// TODO: Open ShopEditPage when implemented
// ShopEditPage page = new ShopEditPage(playerRef, player, plugin, shop);
// player.getPageManager().openCustomPage(ref, store, page);

// For now, open the browse page as a placeholder (owner can still see their shop)
ShopBrowsePage page = new ShopBrowsePage(playerRef, player, plugin, shop);
```

Consequence: closing a shop via `/ksshop close` worked correctly (nameplate `[CLOSED]`, non-owners blocked), but the owner F-keying the same NPC still got the customer-facing browse view and thought "closed shops are interactable like open ones". They were not - the owner just saw the wrong UI because the editor wasn't wired into the NPC interaction path.

### Fix

`ShopBlockInteraction.openEditorPage` now opens the real editor, mirroring the `/ksshop edit` command's setup:
- Builds a `ShopEditPage`
- Wraps its staging container in a `ContainerWindow`
- Registers `onWindowClose` for editor-lock cleanup
- Calls `openCustomPageWithWindows`

Owner F-keys = drag-and-drop editor. Customer F-keys on a closed shop = "shop closed" chat message. Customer F-keys on an open shop = browse UI. Consistent with the `/ksshop edit` behaviour.

### Files Changed
- `interaction/ShopBlockInteraction.java` - `openEditorPage` now opens `ShopEditPage` + `ContainerWindow` instead of `ShopBrowsePage`

---

## [Closed Shops - Keep NPC Visible with [CLOSED] Suffix] - 2026-04-24

### Problem

`/ksshop close` despawned the NPC entirely. Visitors and the owner had no cue that the shop was temporarily unavailable - the spot just looked empty, as if the shop had been deleted. Side effect: `/ksshop open` tried to respawn via the broken world-less `spawnNpc(ShopData)` stub (same TODO path that bit `/ksshop rename` earlier), so re-opening rarely produced a working NPC and a server restart left the closed shop without its NPC forever.

### Fix

Closed shops now stay visible. Three coordinated changes:

1. `ShopNpcManager.applyNameTag` appends `[CLOSED]` to the nameplate when `shop.isOpen() == false` (and it is not a rental shell). The state is legible at a glance, no F-key needed.
2. `onPlayerAddedToWorld` no longer skips closed shops - the NPC comes back on world load just like any other shop. (The earlier rental-shell exemption gets subsumed by this.)
3. `CloseCmd` / `OpenCmd` stopped calling `despawnNpc` / `spawnNpc`. They just flip `shop.isOpen()` and call `refreshNameTag` on the existing entity - the nameplate picks up or drops the suffix.

`ShopBlockInteraction` already blocked the browse UI for non-owners on closed shops (`shop.error.closed` message), so the buyer UX is unchanged.

### Files Changed
- `npc/ShopNpcManager.java` - nameplate `[CLOSED]` suffix + removed closed-shop skip in spawn loop
- `commands/ShopCommand.java` - `CloseCmd` + `OpenCmd` use `refreshNameTag` instead of despawn/spawn

---

## [Missing Slot Texture + Deploy Path Fix] - 2026-04-24

### Problem 1 - Empty slots show a missing-texture X

Opening a shop (Browse UI), the directory, or the editor displayed a red "missing texture" X in every slot. Cause: `ShopBrowse.ui`, `ShopDirectory.ui` and `ShopEdit.ui` all reference `../../Pages/Bank/BankSlot.png`, which only exists in the Bank mod's resources. On servers without the Bank mod installed (the common case), the relative path did not resolve and the UI fell back to the default missing-texture icon.

### Fix 1

Copied the 2x2 `BankSlot.png` into the Shops mod's own resources at the expected relative path (`Common/UI/Custom/Pages/Bank/BankSlot.png`). The three UI files keep working with no source change, and Shops no longer depends on Bank being installed for slot backgrounds to render.

### Problem 2 - deployToServer copies to a dead path

The `deployToServer` Gradle task copied the built JAR to `../server-files/hytale-server-files/mods` relative to the Shops module. That folder does not exist on this developer's machine, so every `./gradlew :mods:shops:deployToServer` call silently put the JAR somewhere noone reads from. The live server at `C:/.../clean-server/Server/mods` kept running the JAR from whenever the user last hand-copied, making most recent "fixes" invisible until manually synced.

### Fix 2

`deployToServer` now reads `hytaleServerModsDir` from `gradle.properties` (or a `-P` override) and falls back to the old dev-tree path if unset. Added:

```
hytaleServerModsDir=C:/Users/Kyuubi D Dragon/Desktop/hytale-downloader/clean-server/Server/mods
```

Task prints the resolved target on every run:
```
deployToServer -> C:/Users/.../Server/mods
```

### Files Changed
- `src/main/resources/Common/UI/Custom/Pages/Bank/BankSlot.png` - new, copied from Bank mod
- `mods/shops/build.gradle.kts` - `deployToServer` task reads configurable target, prints destination
- `gradle.properties` - added `hytaleServerModsDir` override

---

## [/ksshop rename - Duplicate NPC Fix] - 2026-04-24

### Problem

Running `/ksshop rename <newName>` produced a second, ghost NPC at the same spot instead of updating the existing one's nameplate. The original NPC stayed in place (now stale, wrong name on the nameplate for at least a chunk reload) and the new one was spawned on top.

### Root Cause

`RenameCmd.execute()` called `npcManager.respawnNpc(targetShop)` - the world-less overload. That overload delegates to `despawnNpc(shopId)` (async dispatch to world.execute) and then to `spawnNpc(shop)`, which is a stub with a `TODO: Resolve world reference from Hytale API` and does not actually spawn anything. The despawn sometimes landed before the chunk checkpoint and sometimes after; when it landed after, the persisted NPC came back from PersistentModel on the next load while the tracking map no longer knew about it, yielding a duplicate.

### Fix

Replaced the respawn with an in-place nameplate refresh. The rename already has the `World` from the command context, so we dispatch straight to `refreshNameTag(shop, world)`. The NPC entity is kept alive, only the `Nameplate` component's text is flipped - no despawn, no new entity, no chance of a duplicate.

### Files Changed
- `commands/ShopCommand.java` - `RenameCmd.execute` swaps `respawnNpc` for `refreshNameTag`

---

## [Rental Shell NPCs - World-Join Spawn Fix] - 2026-04-24

### Problem

Admin creates a rental slot via `/kssa createrental` or `createrentalauction`. The NPC spawns immediately (works). Server restart. Player re-enters the world. Only the non-rental shop NPCs come back - every rental vacant-shell NPC stays invisible, so players cannot rent any slot until the admin manually respawns them.

Log evidence from a fresh session with 1 player shop + 1 admin shop + 2 rental slots:
```
First player in world 'flat_world' - spawning 4 shop NPC(s) in 3s
Shop NPC spawned: 'TestAdmin1'     ...
Shop NPC spawned: 'TestPlayer1'    ...
[...nothing for the two rental shells...]
Despawned 2 tracked shop NPC(s) + 0 stale ghost(s) on shutdown
```

### Root Cause

`RentalService.createVacantShellShop()` deliberately stores the shell with `open=false` so it does not show up in the public directory or the normal customer buy/sell code paths. Separately, `ShopNpcManager.onPlayerAddedToWorld()` had an unconditional `if (!shop.isOpen()) continue;` skip. The two combined meant rental shells were persisted correctly but never re-spawned on world load - only the `/kssa createrental` in-session spawn path kept them alive, and that path is lost on restart.

### Fix

The closed-shop skip in `onPlayerAddedToWorld` now exempts rental-backed shops:

```java
// Skip closed shops - EXCEPT rental shells. A vacant rental slot
// is stored as ADMIN/open=false so it does not appear in the
// directory or buy/sell flows, but its NPC must still spawn so
// players can press F to rent it.
if (!shop.isOpen() && shop.getRentalSlotId() == null) continue;
```

Rental shells keep `open=false` for directory/buy-flow semantics, but the NPC lifecycle now treats them as always spawn-worthy because their whole purpose is to be F-keyed.

### Files Changed
- `npc/ShopNpcManager.java` - single-line condition refined in `onPlayerAddedToWorld`

---

## [Default Config Template - Schema Rewrite] - 2026-04-24

### Problem

The shipped `resources/defaults/config.json` was out of sync with the Java `ConfigData` schema. It still carried fields that no longer exist in the code (e.g. `allowBuyback`, `transferCost`, `renameCost`, `deleteRefundPercent`, `autoCloseOnEmptyStock`, `autoDeleteAfterInactiveDays`, `rankShops`, `rankItems`, `shopkeeperNameFormat`, `offsetBehindBlock`, etc.) and was missing every field that has been added since (`listingEnabled`, `listingPricePerDay`, `listingMinDays`, `listingMaxDays`, `listingFreeDaysOnCreate`, `spawnDelaySecondsOnJoin`, `skinRetryDelaySeconds`, the entire `rentalStations` block, `claimsIntegration`, `tax.showTaxInPrice`, `tax.taxRecipient`, etc.).

Practical impact: when a new server extracts the template on first load, the live `config.json` does not mention any of the new features. They still work (Java defaults apply at runtime), but the user cannot see or edit them without knowing they exist.

Categories also used a non-existent `displayNameKey` field instead of the `displayName` the Java `CategoryDef` actually reads, and skipped `sortOrder`.

### Fix

Rewrote `defaults/config.json` 1:1 against the current `ConfigData` + inner classes: every nested block, every field, exactly as declared. Removed all legacy fields. Filled in category display names and sort order so the default 8-category list matches what the code actually consumes.

### For Existing Installs

The `load()` path only extracts the template on first load; existing `config.json` files are not overwritten. To pick up the new fields on a running server:
1. Stop the server
2. Delete `<SERVER>/mods/kyuubisoft_shops/config.json`
3. Start the server - fresh config is extracted from the updated template

Or: if the user has customised values they want to keep, they can copy the missing blocks (`listingEnabled`, `npc.spawnDelaySecondsOnJoin`, `npc.skinRetryDelaySeconds`, the `rentalStations` block) into their current file by hand.

### Files Changed
- `src/main/resources/defaults/config.json` - complete rewrite aligned with `ShopConfig.ConfigData`

---

## [Rental Create Commands - Optional Args Fix] - 2026-04-24

### Problem

Running `/kssa createrental TestRentFixed1 100 7` errored with `the wrong number of required argument was specified. expected:1, actual: 2`. Same for `/kssa createrentalauction` when passing more than just the display name.

Cause: both commands declared only `displayName` via `withRequiredArg`, then tried to recover the optional `pricePerDay` / `maxDays` (and the five auction params) by re-parsing `ctx.getInputString()`. Hytale's command dispatcher validates argument count against the declared arg list **before** execute runs, so any trailing tokens were rejected as "too many arguments" and execute never saw them.

### Fix

Switched to the Hytale-idiomatic optional-arg pattern used by `BankCommand`:
- Declared each optional param via `withOptionalArg(name, desc, ArgTypes.INTEGER)` in the constructor and cached the returned `Argument<?, Integer>` handle
- In `execute`, read values via `ctx.provided(arg) ? ctx.get(arg) : configDefault`
- Dropped the manual `parts[]` split and its error handling

Affected commands:
- `/kssa createrental <displayName> [pricePerDay] [maxDays]`
- `/kssa createrentalauction <displayName> [minBid] [bidIncrement] [durationMinutes] [rentalDays]`

### Files Changed
- `commands/ShopAdminCommand.java` - `CreateRentalCmd` and `CreateRentalAuctionCmd` constructors + execute bodies

---

## [Economy Detection - Load Order Fix] - 2026-04-24

### Problem

Server log showed `No economy provider detected - shop transactions disabled` even though Ecotale + VaultUnlocked were installed. Cause: load-order race. Shops ran `economyBridge.detect()` during its own onEnable, but Ecotale registered itself with VaultUnlocked one second later. By the time Ecotale was ready, Shops had already given up and cached `NONE` for the whole session.

### Fix

Added `TheNewEconomy:VaultUnlocked` and `Ecotale:Ecotale` as `OptionalDependencies` in `manifest.json`. Hytale's mod loader now guarantees both are fully initialized before Shops starts, so `detect()` finds a registered provider on first try.

Optional means: if the user doesn't have either mod installed, Shops still loads normally - it just stays in the no-economy fallback path.

### Files Changed
- `src/main/resources/manifest.json` - two additional entries under `OptionalDependencies`

---

## [NPC Skin - Spawn Delay + Fetch Retry] - 2026-04-17

### Problem

Shop NPCs often kept their default (unskinned) appearance even though the server log showed the skin fetch succeeded. Two root causes:

1. **First-player-join race** — when the first player enters a world, every shop NPC in that world spawns immediately and each fires its own PlayerDB fetch. The skin apply (`PlayerSkinComponent` + `ModelComponent`) is pushed to the client while its connection handshake and cosmetics subsystem are still initialising, and the skin update gets dropped / overwritten by the default model.
2. **Transient PlayerDB failure** — if `fetchSkin` returned `null` (network hiccup, API outage), the NPC kept the default appearance for the rest of the session. No retry.

### Fix

**Spawn delay on first player join** — the initial NPC spawn burst now defers by `npc.spawnDelaySecondsOnJoin` seconds (default **3s**) so the client has time to finish connecting before we push a skin burst at it. Uses the existing `ShopPlugin` scheduler; falls through to immediate spawn if the scheduler is unavailable. Range clamped to 0..60.

**Skin fetch retry** — if `fetchSkin` returns `null` on the first attempt, schedule a second attempt after `npc.skinRetryDelaySeconds` seconds (default **5s**). Retries exactly once — enough to cover a momentary PlayerDB blip without spiraling. Range clamped to 0..60.

### Files Changed
- `config/ShopConfig.java` — added `spawnDelaySecondsOnJoin` and `skinRetryDelaySeconds` on `Npc` inner class + clamp validation
- `ShopPlugin.java` — exposed `getScheduler()` accessor for use from the NPC manager
- `npc/ShopNpcManager.java` — `onPlayerAddedToWorld` now schedules the spawn task via the plugin scheduler; refactored `applySkinIfAvailable` to call a new `fetchAndApplySkin(..., attempt)` helper that retries once on null

### Tuning
- Fast LAN / local dev: set `npc.spawnDelaySecondsOnJoin = 0` to match the old behaviour
- Slow clients / big hub worlds with many NPCs: bump to 5-10s
- PlayerDB flaky: bump `npc.skinRetryDelaySeconds` to 10-15s

---

## [Rental Station System - Vacant-Slot NPC Spawning] - 2026-04-16

### Feature Overview

Rental slots now automatically spawn a visible, interactable NPC when created. Players no longer need a manually-placed marker block — they walk up to the NPC, press F, and the rent/bid dialog opens. When a slot is rented, the vacant NPC is seamlessly replaced by the renter's shop NPC. When the rental expires, the renter's NPC despawns and the vacant NPC respawns.

### How It Works

**Vacant Shell ShopData** — when admin creates a slot via `/kssa createrental` or `/kssa createrentalauction`, the `RentalService` now also creates a lightweight "shell" ShopData (type=ADMIN, no owner, empty items, `rentalSlotId` set, `open=false`, `listedUntil=0`). The existing `ShopNpcManager` pipeline spawns an interactable NPC for it just like any other admin shop. The nameplate shows `"SlotName [100g/day]"` for FIXED or `"SlotName [AUCTION 500g]"` for auction mode.

**Interaction Routing** — when a player F-keys the vacant NPC, the existing Path 1 (NPC entity → shopId lookup) finds the shell ShopData. A new check in `ShopBlockInteraction.handleInteraction` detects `shop.isRentalBacked() && shop.isAdminShop()` (= vacant shell) and routes to `openRentalConfirm` → `RentalRentConfirmPage` (FIXED) or `RentalBidPage` (AUCTION). Rented slots (type=PLAYER, rentalSlotId set) go through the normal owner/visitor routing as before.

**Lifecycle:**
- `/kssa createrental` → creates RentalSlotData + shell ShopData + spawns NPC immediately
- Player rents → `deleteVacantShellShop()` despawns shell NPC + deletes shell ShopData → creates renter's ShopData + spawns renter's NPC via `spawnShopNpcFromPlayer()`
- Rental expires → `expireSlot()` mails items back, despawns renter NPC, deletes renter ShopData → `createVacantShellShop()` recreates the shell + NPC will spawn on next world init
- `/kssa deleterental` → `deleteVacantShellShop()` cleans up the shell first, then removes the slot row

### New Methods

**`RentalService`:**
- `createVacantShellShop(RentalSlotData)` — builds a shell ShopData (ADMIN, nameplate with price info) and persists via `shopManager.createShop()`
- `deleteVacantShellShop(RentalSlotData)` — looks up the shell by `rentalSlotId`, despawns its NPC, deletes it. Only operates on ADMIN-type shells (ignores the renter's PLAYER shop if the slot is currently rented)
- `buildVacantName(RentalSlotData)` — formats the nameplate: `"SlotName [100g/day]"` or `"SlotName [AUCTION 500g]"`
- `spawnShopNpcFromPlayer(ShopData, Player)` — resolves World from the player entity and dispatches `spawnNpcAtPosition` to the world thread. Falls back to deferred spawn if player is null (auction win while offline)

**`ShopManager`:**
- `getShopByRentalSlotId(UUID)` — linear scan over the shops cache to find the shell or renter shop matching a given rental slot

### Modified Files

- **`RentalService.java`** — `createFixedSlot` / `createAuctionSlot` now call `createVacantShellShop()`. `deleteSlot` calls `deleteVacantShellShop()`. `rentSlot` calls `deleteVacantShellShop()` + `spawnShopNpcFromPlayer()`. `expireSlot` calls `createVacantShellShop()`. `finalizeAuction` calls `deleteVacantShellShop()` + `spawnShopNpcFromPlayer()`.
- **`ShopBlockInteraction.java`** — new vacant-shell detection branch after Path 1/2 shop lookup: `shop.isRentalBacked() && shop.isAdminShop()` → route to rental page. Path 3 proximity fallback kept as safety net.
- **`ShopAdminCommand.java`** — `CreateRentalCmd` / `CreateRentalAuctionCmd` now immediately spawn the shell NPC via `world.execute(() -> spawnNpcAtPosition(...))` so the admin sees the NPC appear right after creating the slot.
- **`ShopManager.java`** — new `getShopByRentalSlotId(UUID)` lookup.

### Verification

- `./gradlew :mods:shops:build` — BUILD SUCCESSFUL.

## [Rental Station System - Admin Rentals Tab] - 2026-04-15

### Feature Overview

New **Rentals** tab in the existing `ShopAdminPage` sidebar — admins now have a visual grid to see every rental slot on the server at a glance, filter by state, search by name/renter, and run force-expire / delete directly from the UI instead of needing to copy slot UUIDs into chat commands.

### Changes

**`ShopAdminPage.java`**
- Added `"rentals"` to `TABS` / `TAB_IDS` / `PANEL_IDS` (new sidebar position between Player Shops and Transactions).
- New `RENTALS_PER_PAGE = 8` constant + state fields: `rentalsPage`, `rentalsSearch`, `rentalsFilter` (all/vacant/auction/rented), `cachedRentals` list.
- `handleDataEvent` extended with search / pagination / filter-tab action cases, plus row action dispatching (`rentals_expire_N`, `rentals_delete_N`) using the existing `action.startsWith(...)` pattern from the Shops / Players tabs.
- **`populateRentals(ui)`** — mirrors the `populateShops` / `populatePlayers` structure. Fetches slots from `RentalService.getAllSlots()`, applies the filter (all / vacant / auction / rented), text-search filter matches against `displayName` + `rentedByName`, sorts case-insensitive by name, paginates at 8 rows. Per row sets: name, mode badge (AUCTION gold / FIXED green), state (VACANT green / AUCTION gold / RENTED cyan), price string (`100g/day` / `500g bid` / `500g min`), renter cell with remaining time. The EXPIRE button hides on non-rented rows via `Visible = false` — DEL stays visible so admins can delete vacant/auction slots too.
- **`matchesRentalFilter(slot, now)`** — switch on `rentalsFilter` returns true for the tab's membership rule (VACANT = no renter + auction not open; AUCTION = auction open; RENTED = has renter; ALL = everything).
- **`handleRentalForceExpire(action)`** — parses row index, looks up the slot in `cachedRentals`, calls `rentalService.expireSlot(slot, FORCE_EXPIRED)`. Items + balance get mailed back via the existing expiry pipeline.
- **`handleRentalDelete(action)`** — calls `rentalService.deleteSlot(slot.getId())` which handles the force-expire-on-delete path internally if the slot is currently rented.
- `bindAllEvents` extended with: RentSearchBtn, four filter buttons (RentFilterAll/Vacant/Auction/Rented), prev/next pagination, and `RentExpireBtn0..7` / `RentDeleteBtn0..7` per-row bindings.
- Import adds `RentalService`, `RentalSlotData`, `RentalExpiredEvent`.

**`ShopAdmin.ui`**
- New sidebar button `#SATabRentals` with the same sidebar styling as the other management tabs (black bg, hover ffffff10, active indicator strip).
- New `#SAPanelRentals` group (defaults invisible like all non-general panels). Contents:
  - **Search row** — `#RentSearchField` + `#RentSearchBtn` identical to the Shops/Players search UI.
  - **Filter pills** — 4 buttons (`RentFilterAll` / `RentFilterVacant` / `RentFilterAuction` / `RentFilterRented`) each with a 3px indicator strip sub-group (`RentFilterAllInd`, etc.) that the Java side toggles to `#00bfff` to mark the active tab.
  - **Column header** — SLOT / MODE / STATE / PRICE / RENTER / ENDS / ACTIONS.
  - **8 rows** (`RentRow0..7`) each with `RentName`, `RentMode`, `RentState`, `RentPrice`, `RentRenter` labels + `RentExpireBtn` (amber "EXP") and `RentDeleteBtn` (red "DEL") per row.
  - **Empty state** — `#RentEmpty` falls back to a hint message when no slots match the active filter.
  - **Pagination** — `#RentPrevBtn` / `#RentPageInfo` / `#RentNextBtn` mirroring the other tabs.

### Admin workflow

1. `/kssa admin` → opens `ShopAdminPage`.
2. Click **Rentals** in the sidebar.
3. Use the filter pills to narrow to just auction slots or just rented ones.
4. Optional: type a slot name or renter username in the search field + click Search.
5. Per row:
   - **EXP** button → fires the normal expiry path immediately (items + shop balance get mailed to the renter, backing shop deleted, slot returns to VACANT). Only visible on rented rows.
   - **DEL** button → removes the slot entirely. If the slot is currently rented, the delete path first runs a force-expire (so the renter gets their items back), then deletes the slot row + clears its bid history.

### Verification

- **`./gradlew :mods:shops:build`** — BUILD SUCCESSFUL. Only pre-existing deprecation warnings from `getTransformComponent` / `getPlayerRef` / `getUuid`. No new warnings introduced by this commit.
- **Smoke test (in-game)** to run:
  1. `/kssa createrental TestSlot 100 3` + `/kssa createrentalauction TestAuction 500 50 10 7` → creates one FIXED and one AUCTION slot.
  2. `/kssa admin` → Rentals tab shows 2 rows, one green VACANT, one gold AUCTION with countdown.
  3. Filter button **AUCTION** → only the auction row remains visible, filter pill lights up `#00bfff`.
  4. Player A rents TestSlot via F-key → admin tab now shows TestSlot as RENTED (cyan) with expiry countdown.
  5. **EXP** button on TestSlot → items + balance go to Player A's mailbox, slot returns to VACANT without admin ever typing the slot UUID.
  6. **DEL** button on TestAuction → slot removed, bid history cleared, chat status shows `Deleted rental slot: TestAuction`.

### Why this design

The plan's earlier "deferred" note mentioned cloning the whole tab machinery as a large addition — that concern turned out to be overstated. The tab code is ~180 lines (comparable to `populateShops`), and the .ui additions are routine row templates following the Players tab pattern. Adding the tab now removes a real friction point for admins (they had to copy-paste UUIDs from `/kssa listrentals` into `/kssa forceexpirerental` / `/kssa deleterental`), and the `RentalService.expireSlot` + `deleteSlot` API already existed and was well-tested from Phase 1/2.

**Still deferred:** per-row TELEPORT button (would need a world-ref + dispatch to the world thread from the admin page), station hub NPC grouping (separate feature), vacant-slot NPC spawning (separate feature).

## [Rental Station System - Phase 2/3 (Auction + Station Browse + MyRentals UI)] - 2026-04-15

### Phase 2 — Auction Mode

Full auction bidding on rental slots. Admins spin up auction slots with a min bid, bid increment, duration, and rental window. Players F-key the slot (proximity routing), see a live-countdown page with bid history, place bids without their gold being locked up, and the winner is charged when the auction closes. Anti-sniping, outbid chat notifications, ending-soon broadcast, and failover for insufficient-funds winners all handled.

**New files:**
- **`rental/event/RentalBidPlacedEvent.java`** — fired per bid with current amount, bidder, previous high bid, and the (possibly-extended) auction end timestamp.
- **`rental/event/RentalAuctionWonEvent.java`** — fired only after a winner is successfully charged and a rental is started.
- **`rental/ui/RentalBidPage.java`** + **`resources/Common/UI/Custom/Pages/Shop/RentalBid.ui`** — 560x620 full auction page. Top block shows time remaining (amber countdown), current high bid (gold), high bidder (cyan), and the viewer's status line (`You are the high bidder!` / `No bid placed yet` / `You have been outbid`). Middle block is a 6-row bid history table populated from `shop_rental_bids`, with an empty-state label for first-bid-ever. Bottom block has the minimum-required-bid readout, the viewer's live balance, a `SliderNumberField` ranging from `nextMinimumBid` to `max(min*10, 10000)`, and `PLACE BID` / `CLOSE` buttons. Registered with `RentalService` on build, unregistered on dismiss — the live-countdown scheduler tick every 2s calls `refreshFromTick()` so other players' bids propagate within ~2s without client polling.

**RentalService extensions:**
- **`createAuctionSlot(...)`** — mirror of `createFixedSlot` that starts the first auction round immediately (`auctionEndsAt = now + durationMinutes * 60_000`).
- **`placeBid(PlayerRef, Player, UUID, int)` → `BidResult`** — typed result enum (SUCCESS / SLOT_NOT_FOUND / NOT_AUCTION / AUCTION_CLOSED / NO_PERMISSION / BID_TOO_LOW / NOT_ENOUGH_FUNDS / ECONOMY_UNAVAILABLE / OWN_BID / FAILED). Bids are NOT withdrawn at bid time — the winner is charged at finalize. Per-slot lock acquired before the state mutation. Anti-sniping: if the bid arrives inside the last `auctionAntiSnipingSeconds` of the auction, `auctionEndsAt` is bumped by that many seconds AND `endingSoonBroadcast` is re-armed so the broadcast can fire again for the extended window. Outbid chat message goes to the previous high bidder via `sendChatMessage(uuid, text, color)` — silent if the player is offline (they weren't committed financially, so no refund, no spam).
- **`finalizeAuction(RentalSlotData)`** — caller holds the slot lock. No high bidder → `handleEmptyAuction` (config-driven RESTART/VACANT/DELETE). Has a high bidder → `economyBridge.withdraw(winner, winningBid)`. On success: builds the backing `ShopData`, copies all slot rental fields onto it, clears auction state, persists, deletes bid history, fires both `RentalStartedEvent` + `RentalAuctionWonEvent`, and chat-messages the winner with the rental end time. On failure (insufficient funds): chat-messages the winner "Your auction win was forfeited", clears bidder state, restarts the auction per config.
- **`handleEmptyAuction(slot)`** — switches on `config.rentalStations.onEmptyAuction`: `"DELETE"` removes the slot entirely (with bid history cleanup); `"VACANT"` clears `auctionEndsAt` and waits for admin re-arm; default `"RESTART"` spawns a fresh round with the same duration.
- **`restartAuction(slot)`** — helper that flips `auctionEndsAt` to `now + duration*60_000` and resets the `endingSoonBroadcast` flag.
- **`broadcastEndingSoon(slot)`** — sends a chat broadcast to every online player once per auction when the time-remaining drops below `auctionEndingSoonSeconds`. Flag on the slot prevents repeat firing.
- **`formatRemainingTime(ms)`** — shared formatter: `"2d 14h"` / `"5h 32m"` / `"45s"` — used by bid page, station browse, my-rentals page, chat messages.
- **`scanExpired()`** extended — in addition to rental-window expiry (Phase 1), now also detects auction-close (`auctionEndsAt <= now` with no renter) and runs `finalizeAuction`. Also scans for the ending-soon broadcast window in every tick iteration so the 60s tick is enough to catch every slot.
- **Live countdown tick infrastructure** — `registerOpenBidPage(UUID, RentalBidPage)` / `unregisterOpenBidPage(UUID)` / `tickOpenPages()`. `ConcurrentHashMap<UUID, RentalBidPage>` tracks open pages; the 2s scheduler tick iterates and calls each page's `refreshFromTick()` method, which re-runs `buildUI` against the latest slot state. Safe against pages dismissed mid-tick (exception is logged, iteration continues).

**Bid history persistence (new table):**
- **`shop_rental_bids`** (MySQL + SQLite) — columns: `id` (auto-increment), `slot_id`, `bidder_uuid`, `bidder_name`, `amount`, `timestamp`. MySQL declares `INDEX idx_rental_bids_slot (slot_id, timestamp DESC)` inline; SQLite adds the same via explicit `CREATE INDEX IF NOT EXISTS` after the table create.
- **CRUD**: `insertRentalBid(slotId, uuid, name, amount, ts)`, `loadRecentBids(slotId, limit)` returns a `List<RentalBidRecord>` ordered by `timestamp DESC`, `deleteBidsForSlot(slotId)` called on slot delete and auction finalize so closed auctions don't leak history rows.

**Admin commands:**
- **`/kssa createrentalauction <displayName> [minBid] [bidIncrement] [durationMinutes] [rentalDays]`** — creates an auction slot at the caller's position. All numeric args default to `config.rentalStations.defaultMinBid / defaultBidIncrement / defaultAuctionDurationMinutes / defaultMaxDays` respectively. Auction round starts immediately on creation.

**Interaction routing:**
- **Auction slots now open `RentalBidPage`** instead of the Phase 1 placeholder. `ShopBlockInteraction.openRentalConfirm` switches on `slot.getMode()` — AUCTION → `RentalBidPage`, FIXED → `RentalRentConfirmPage`.

**Plugin lifecycle:**
- **12c. Live countdown tick** — new `scheduleAtFixedRate(rentalService::tickOpenPages, 2, 2, SECONDS)` wired alongside the Phase 1 expiry tick. Only runs when `config.rentalStations.enabled`.
- **`ShopPlugin.getOnlinePlayer(UUID)` + `getOnlinePlayers()`** — public accessors on the already-tracked `onlinePlayers` map so RentalService can send out-of-band chat messages and broadcast-to-all.

### Phase 3 — Station Browse + MyRentals UI

**New files:**
- **`rental/ui/MyRentalsPage.java`** + **`resources/Common/UI/Custom/Pages/Shop/MyRentals.ui`** — 740x560 self-service page opened by `/ksshop myrentals`. Replaces the Phase 1 chat-only MVP. Column headers: Slot / Expires / Items / Revenue / Actions. Up to 6 rows, each with a 3-column layout + an EXTEND / RELEASE button pair. Empty state when the player has no rentals. Per-row bindings use the row-indexed action pattern (`extend_0`, `release_0`, etc.) from `ShopAdminPage` to sidestep the single-key `EventData.of(key, value)` API — the page caches `rowSlotIds` during `buildUI` and looks up the target slot from the row index on action. EXTEND defaults to 1 day per click; RELEASE hands off to `RentalService.releaseEarly`.
- **`rental/ui/RentalStationPage.java`** + **`resources/Common/UI/Custom/Pages/Shop/RentalStation.ui`** — 860x620 multi-slot browse. Opened via new `/ksshop rentalstations` command. Tabs: ALL / VACANT / AUCTION / RENTED implemented via `tab_<NAME>` action strings. 6 rows per page with PREV / NEXT pagination. Each row shows slot name, mode badge, state string (Available / `X Gold - ends Ymm` / Rented by name + time left), and a RENT / BID / OCCUPIED action button. Clicking a row's action closes the station page and opens either `RentalRentConfirmPage` (FIXED) or `RentalBidPage` (AUCTION). Empty state when the current world has zero slots / zero slots match the active filter. Also uses the row-indexed action pattern.

**Player commands:**
- **`/ksshop myrentals`** (already existed as a chat MVP) — now opens `MyRentalsPage` for a proper interactive UI.
- **`/ksshop rentalstations`** — opens the multi-slot `RentalStationPage` scoped to the caller's current world.

### i18n (v25)

New keys: `auction_closed`, `bid_too_low`, `own_bid`, `bid_placed`, `station.*` (title, tab_all/vacant/auction/rented, state_vacant/auction/rented, action_rent/bid/occupied, empty), `admin.*` (title, force_expire, delete, teleport — for the deferred admin tab). `_localization_version` bumped `24 → 25`.

### What was deferred

- **Admin Rentals tab in `ShopAdminPage`** — cloning the existing sidebar+tabs machinery for a single new tab is a large addition that mostly duplicates functionality admins already have via `/kssa listrentals`, `/kssa deleterental`, `/kssa forceexpirerental`, and the player-visible `RentalStationPage`. Landing as its own polish commit.
- **Station hub NPC** — the `stationId` concept (grouping multiple slots under one hub NPC) is DB-scaffolded but the NPC spawn + interaction routing aren't wired. Phase 1/2 slots are standalone; `stationId` stays null until the hub work lands.
- **Vacant-slot NPC spawning** — still uses the Phase 1 proximity model (admin places any marker block, the F-key handler resolves to the nearest vacant slot within 3 blocks). Proper vacant-NPC entities with live-updating nameplates are a dedicated followup.
- **`RentalStationListPage`** — the plan's optional "compass / teleport" helper page wasn't needed because `/ksshop rentalstations` gives players the same discovery.
- **Proxy bidding** — intentionally dropped from the plan; no change.

### Verification

- **`./gradlew :mods:shops:build`** — BUILD SUCCESSFUL. Only pre-existing deprecation warnings (`getTransformComponent`, `getPlayerRef`, `getUuid`). The `EventData.of(key, value)` two-arg constraint forced a row-indexed action pattern in both `MyRentalsPage` and `RentalStationPage` — caught on first compile and fixed before the final build.
- **End-to-end auction smoke test (to run in-game)**:
  1. `/kssa createrentalauction HotSlot 500 50 10 7` at a position → vacant NPC region, 10 minute auction starts, 7 rental days on win.
  2. Player A F-keys near the position → RentalBidPage opens, slider at min 500. Bids 500 → row appears in history, "You are the high bidder" status lights up.
  3. Player B opens the same slot → sees Player A's 500 bid, "No bid placed yet" → bids 550 → Player A gets `You have been outbid on HotSlot. Current high bid: 550 Gold.` chat message, Player A's open page (if still open) updates on the next 2s tick to show the new high bid.
  4. Wait for last 30s of auction → broadcast `Auction HotSlot ends in 30s! Current bid: 550 Gold` fires once to every online player.
  5. Player A snipes at 550+50=600 with 5s left → anti-sniping bumps auction end by 30s. Fresh auction end broadcast available in the next tick.
  6. Clock runs out → finalize withdraws 600g from Player A, creates a ShopData row linked to the slot, `RentalAuctionWonEvent` fires, chat message `You won HotSlot! Rental active until 7d 0h`. Slot now shows `RENTED by PlayerA (7d left)` in `/kssa listrentals`.
  7. `/ksshop rentalstations` → RENTED tab shows the slot. Player B F-keys it → normal `ShopBrowsePage` (buyer flow) since there's a backing `ShopData` row.

### Pattern References

- **Row-indexed action encoding** — `ShopAdminPage.java:821` uses `"shops_delete_" + i` for per-row actions. Both `MyRentalsPage` and `RentalStationPage` adopt the same pattern and cache the row → slot UUID mapping during `buildUI`.
- **Live countdown tick** — `ShopDirectoryPage` uses a similar scheduler-driven refresh for pagination. RentalService's `openBidPages` registry is lighter: no concurrent modification, just a `ConcurrentHashMap` iteration with per-entry try/catch.
- **Out-of-band chat messages** — `Player.sendMessage(Message.raw(text).color(hex))` via the cached `onlinePlayers` map. Silent no-op for offline players.
- **Auction state machine** — the in-plan diagram `IDLE → AUCTION_OPEN → (finalize) → RENTED → (expire) → AUCTION_OPEN` is the exact flow the scanExpired tick implements. Forfeit-on-no-funds branch restarts auction with next-highest bidder skipped (v1 behavior: just restart fresh, no runner-up bookkeeping).

## [Rental Station System - Phase 1 (Fixed Price)] - 2026-04-14

### Feature Overview

New parallel shop creation path: admins place persistent leasable shop slots at fixed world positions, players walk up and rent for X days at a fixed gold price. When the rental expires (or the renter releases early), every stocked item is mailed back automatically and the slot returns to vacant. Existing `/ksshop create` + admin shop flows are completely unchanged — this is additive. Auction mode (Phase 2) and station-hub browsing (Phase 3) land later.

### New Files

- **`rental/RentalSlotData.java`** — persistent slot entity with FIXED/AUCTION mode, price/day, max days, and runtime rental state (rentedBy, rentedUntil, auction fields). `fromDatabase(...)` factory for DB load path; `isVacant()`, `isAuctionOpen()`, `isRentalExpired()`, `clearRuntimeState()` helpers.
- **`rental/RentalService.java`** — core logic. `loadAll()`, `saveDirty()`, `createFixedSlot(...)`, `deleteSlot(...)`, `rentSlot(...)`, `extendRental(...)`, `releaseEarly(...)`, `expireSlot(...)`, `scanExpired()`, `countActiveRentalsFor(...)`, `getSlotsForRenter(...)`. Per-slot `synchronized` locks via `ConcurrentHashMap<UUID, Object> slotLocks` so racing renters and expiry ticks don't collide. Typed `RentResult` enum for rent failures (SLOT_NOT_FOUND, NOT_VACANT, LIMIT_REACHED, NO_PERMISSION, INVALID_DAYS, NOT_ENOUGH_FUNDS, ECONOMY_UNAVAILABLE, FAILED).
- **`rental/RentalExpiryTask.java`** — scheduler tick body, wraps `RentalService.scanExpired()` in a try/catch so a single broken slot never kills the whole tick.
- **`rental/event/RentalStartedEvent.java`** / **`RentalExpiredEvent.java`** — event bus payloads with renter UUID + name, slot display name, expiry timestamp, mailed item/balance totals, and a `Reason` enum (EXPIRED / FORCE_EXPIRED / RELEASED_EARLY) for the expired event so downstream listeners can distinguish.
- **`rental/ui/RentalRentConfirmPage.java`** + **`resources/Common/UI/Custom/Pages/Shop/RentalRentConfirm.ui`** — 440x420 confirm dialog. Shows slot name, position, price/day, max days, a `SliderNumberField` for 1..maxDays, live total cost, and the player's current-rentals / cap readout. `RENT NOW` / `CANCEL` buttons. Fires `RentalService.rentSlot(...)`, on success closes the page and chat-messages the renter; on failure surfaces the typed `RentResult` as an i18n error key.

### Database Schema

- **New table `shop_rental_slots`** in both MySQL (`ENGINE=InnoDB`, four inline indexes) and SQLite variants. Columns: `id`, `world_name`, `pos_x/y/z`, `npc_rot_y`, `display_name`, `station_id`, `mode`, `max_days`, `price_per_day`, `min_bid`, `bid_increment`, `auction_duration_minutes`, `created_at`, `rented_shop_id`, `rented_by`, `rented_by_name`, `rented_until`, `auction_ends_at`, `current_high_bidder`, `current_high_bidder_name`, `current_high_bid`, `ending_soon_broadcast`. Indexes on `world_name` (station lookup), `rented_until` (expiry scan), `auction_ends_at` (Phase 2), `rented_by` (MyRentalsCmd lookup). SQLite gets explicit `CREATE INDEX IF NOT EXISTS` statements after the table create; MySQL declares them inline.
- **ALTER TABLE `shop_shops`** — two new columns, fail-silent with `catch (SQLException ignored)` using the same migration idiom as the existing `listed_until` / `packed` / `show_name_tag` block: `rental_slot_id VARCHAR(36) DEFAULT NULL` and `rental_expires_at BIGINT DEFAULT 0`. Rental-backed shops mirror the live `RentalSlotData.rentedUntil` in `rental_expires_at` so directory / buy filtering can short-circuit without a join.
- **New DB CRUD**: `loadAllRentalSlots()`, `loadRentalSlot(UUID)`, `saveRentalSlot(...)`, `deleteRentalSlot(UUID)`, private `readRentalSlot(ResultSet)` — follows the exact same pattern as `readShop` / `saveShop` (UUID null-safety, mode enum fallback to FIXED, REPLACE INTO / INSERT OR REPLACE INTO split for MySQL vs SQLite).
- **`saveShop` / `readShop`** — extended to include `rental_slot_id` + `rental_expires_at`. Read path uses `try { ... } catch (SQLException ignored)` for both new columns so legacy DBs without the migration still load cleanly with null / 0 defaults.

### Config

- **`ShopConfig.RentalStations` nested class** with defaults: `enabled=true`, `defaultPricePerDay=100`, `defaultMaxDays=7`, `defaultMinBid=500` (Phase 2), `defaultBidIncrement=10` (Phase 2), `defaultAuctionDurationMinutes=60` (Phase 2), `maxConcurrentRentalsDefault=1`, `onEmptyAuction="RESTART"` (Phase 2: RESTART / VACANT / DELETE), `auctionAntiSnipingSeconds=30` (Phase 2), `auctionEndingSoonSeconds=60` (Phase 2), `extendMaxDays=7`, `releaseEarlyGoldRefundFraction=0.0`, `rentalShopsListFree=true` (rental-backed shops skip the directory listing fee since the rental cost already covers visibility).
- **Clamp validation** alongside the existing `maxShopsPerPlayer` / `tax` / `ratings` clamp block — negatives clamped to 0, invalid enum falls back to `"RESTART"`, refund fraction clamped to 0..1.

### Permissions

- **`ks.rental.rent`** default true — may rent fixed-price slots.
- **`ks.rental.bid`** (Phase 2) — may bid in auctions.
- **`ks.rental.limit.N`** — max N concurrent rentals per player, resolved via `PermissionLimits.resolveMaxRentals(Player, int)`. Pattern mirrors the existing `ks.shop.limit.shops.N` scan (scans 1..200, highest matching N wins, global default as floor).

### Admin Commands (ShopAdminCommand)

- **`/kssa createrental <displayName> [pricePerDay] [maxDays]`** — creates a FIXED-price slot at the caller's position. `pricePerDay` defaults to `config.rentalStations.defaultPricePerDay`, `maxDays` defaults to `config.rentalStations.defaultMaxDays`. Persists to DB and logs the new slot UUID in chat.
- **`/kssa deleterental <slotId>`** — removes a slot. If a rental is currently active, the renter gets a full mailbox refund first via the normal expiry path (force-expire).
- **`/kssa listrentals`** — chat list of every rental slot in the caller's current world. Shows mode badge, current state (VACANT / RENTED by X with hours remaining / AUCTION high bid), and the slot UUID for copy-paste into other commands.
- **`/kssa forceexpirerental <slotId>`** — admin override that runs the normal expiry path immediately (items mailed to renter, backing shop deleted, slot returns to vacant). Used when a renter bricks their shop and needs manual intervention.

### Player Commands (ShopCommand)

- **`/ksshop myrentals`** — chat list of the caller's active rentals with time-remaining and slot UUIDs. MVP-simple for Phase 1; a full `MyRentalsPage` with EXTEND / RELEASE EARLY buttons lands in Phase 2.
- **`/ksshop releaserental <slotId>`** — voluntary early release. Items + shop balance mailed back; gold refund follows `releaseEarlyGoldRefundFraction` config (default 0 — releasing early is a commitment cost).

### Interaction Routing (ShopBlockInteraction)

- **Path 3: Vacant rental slot proximity** — new branch added after the existing NPC entity / block proximity paths. If no shop row matches the F-keyed target, the handler scans every rental slot in the player's current world and checks whether the target block (or entity position, if an entity was F-keyed) is within 3 blocks of a vacant slot. If so, the event is cancelled and `RentalRentConfirmPage` opens for that slot. **Rented slots** go through the existing path unchanged — they have a real `ShopData` row backing them, so F-keying a rented-slot NPC opens the normal `ShopBrowsePage` (for buyers) or the editor (for the renter), identical to any other player shop. This is intentional: the rental's backing shop IS a player shop.
- **Auction-mode vacant slots** — placeholder that sends a "Phase 2 not yet implemented" chat message. Non-breaking: the interaction is still cancelled so the player doesn't get a ghost event.

### Plugin Wiring (ShopPlugin)

- **`rentalService = new RentalService(...)` + `rentalService.loadAll()`** in `setup()` step 6b, right after the `ShopNpcManager` so the cache is populated before any tick runs.
- **`RentalExpiryTask` scheduler** — new `scheduleAtFixedRate(rentalExpiryTask::tick, 60, 60, TimeUnit.SECONDS)` alongside the existing rent-collection tick at step 12b. Gated by `config.rentalStations.enabled`.
- **`autoSave()`** — now also calls `rentalService.saveDirty()` so bid timestamps + expiry flips actually persist between restarts.
- **`shutdown()`** — same `rentalService.saveDirty()` call so runtime state flush-outs before the DB closes.
- **`getRentalService()` getter** added to the public accessor block so UI pages / command classes can reach the service.

### i18n (bumped to v24)

New `shop.rental.*` keys: `rent_success`, `rent_failed`, `already_rented`, `limit_reached`, `invalid_days`, `not_enough_funds`, `slot_not_found`, `expired_chat`, `released_chat`, `extend_success`, `extend_failed`, Phase 2 stubs (`auction_won`, `auction_outbid`, `auction_ending`, `auction_extended`), plus `myrentals.*` (title, empty, expires_in, open_editor, extend, release) for the upcoming Phase 2 UI. `_localization_version` bumped `23 -> 24`, backup + regen on next server restart.

### Verification

- **`./gradlew :mods:shops:build`** — BUILD SUCCESSFUL. Only pre-existing deprecation warnings from `getTransformComponent()` / `getPlayerRef()` / `getUuid()` API migrations — nothing introduced by this commit.
- **Flow smoke test (to run in-game)** — `/kssa createrental TestSlot 100 3` at position → `/kssa listrentals` shows VACANT → F-key near the slot → RentalRentConfirmPage opens → slider picks 2 days → `RENT NOW` → withdraw 200 gold, ShopData row created with `rentalSlotId` + `rentalExpiresAt` set, chat confirmation. Manually set `rented_until` to `now - 1` in the DB → wait 60s → expiry tick mails items + balance back to mailbox, deletes backing shop, slot returns to VACANT in `listrentals`.

### Pattern References

- **Item refund via mailbox** — `ShopService.pickupShop:972-1008` is the source pattern; `RentalService.expireSlot` reuses the exact `createItemMail` (with BSON metadata preservation) + `createMoneyMail` loop.
- **Per-slot concurrency** — `ConcurrentHashMap<UUID, Object>` lock interning is the same idiom other mods use for chunk / entity mutation paths.
- **DB migration idempotency** — `ShopDatabase.java:335-425` ALTER TABLE block with try/catch(SQLException ignored). Phase 1 adds two new migrations to the same block.
- **Scheduler registration** — `scheduleAtFixedRate` after the existing rent-collection tick; same pattern as auto-save, orphan sweep, rotation, rent collection.
- **Permission scan** — `PermissionLimits.resolveMaxShops` is the template; new `resolveMaxRentals` is a copy with a different prefix.

### Non-Goals (explicitly out of Phase 1 scope — deferred to Phase 2/3)

- Auction mode (bid page, anti-sniping, outbid notifications, auction finalize)
- Station hub NPC browsing multiple slots at once
- Admin `ShopAdminPage` "Rentals" tab (admins use commands for now)
- Full `MyRentalsPage` UI (chat-only for MVP)
- Station group (`stationId`) semantics beyond the DB column — slots created via `/kssa createrental` all get `stationId=null` for now
- Vacant-slot NPC spawning (admins physically place a marker block / sign at the position for Phase 1; the proximity check handles the F-key)
- Nameplate state rendering for rental slots (vacant / rented labels)
- Guild-owned rentals, rental-to-rental transfer, subletting

## [Unreleased] - 2026-04-13

### Items Tab: Colored Tooltip (Minecraft §-codes) + Dynamic Stock Color

- **Tooltip Colors (`populateItemSearchGrid`)** - The `ItemGridSlot.setDescription` string in the Items-tab search grid now uses Minecraft-style `§`-codes (`\u00a7` + color char) for per-line coloring. Bank's `KsLangCommand` and ChatPlus both already use these codes for chat messages so the Hytale text pipeline supports them end-to-end; the same codes should render in the native tooltip renderer. If Hytale strips them, the lines fall back to plain text with a few visible `§` chars — ugly but non-breaking.
- **Tooltip Palette** - `§b Shop: §f<name>` (aqua label + white name), `§7 by §f<owner>` (gray "by" + white name) / `§d Admin Shop` for admin listings, `§e Price: §6<n> §e Gold` (yellow label + gold number), `§a Stock: §f<n>` (green label, value color dynamic: `§a` green at 6+, `§e` yellow at 3-5, `§c` red at 1-2), `§8 <click hint>` (dark gray so the hint doesn't fight with the info). Unlimited-stock items get `§b Stock: §f Unlimited` (aqua).
- **Buy Overlay - Price Distinction** - `#DirConfirmPrice.Style.TextColor` is now pushed from Java as `#ffb74d` (warm amber) instead of the original `#ffd700` gold so the per-unit price and the grand-total price (still `#ffd700` gold) are visually distinct. Before, both lines were the same gold and the eye had to re-read to tell them apart.
- **Buy Overlay - Dynamic Stock Color** - `#DirConfirmStock.Style.TextColor` is now calculated per render: `#26c6da` cyan for unlimited stock, `#66bb6a` green for 11+ items, `#ffb74d` amber for 3-10 items, `#ff5252` red for 1-2 items. Gives the buyer immediate visual feedback on scarcity without reading the number.
- **Buy Overlay - Admin Shop Accent** - Admin-shop listings now use `#ce93d8` magenta on the owner line (which shows "Admin Shop" in that case) so admin listings are visually called out. Player listings keep the existing `#9fa8da` lavender owner color.
- **Why §-Codes for the Tooltip** - `ItemGridSlot.setName(String)` / `setDescription(String)` accept plain Strings only; there is no `Style.TextColor`-style override for the slot tooltip. Minecraft `§`-codes are the only in-band mechanism Hytale exposes for per-segment text coloring inside a single String. They are the same codes Hytale's own chat renderer parses, so if the slot tooltip goes through the same pipeline (likely), the colors will render automatically.
- **No i18n Changes** - The new colors live entirely in `ShopDirectoryPage.java`; the existing `shop.directory.tooltip.*` keys were bypassed in favor of inline `§`-coded strings so the color format is a Java-side concern, not something translators need to handle. The `click_hint` key is still looked up via `i18n.get(...)` and then prefixed with `§8`.
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL.
- **Pattern Reference** - `mods/bank/src/main/java/com/hytale/bank/kslang/KsLangCommand.java:55` uses `\u00a7a[KsLang] \u00a77<text> \u00a7f<value>` - the exact same `§`-code format this commit adopts for the tooltip.

### Browse Page Back Button + Quantity Slider in Both Confirm Overlays

- **Back Button on `ShopBrowsePage`** - A new `< BACK` button is anchored at the left side of the browse page's info bar (left of the owner / rating / item-count row). Clicking it opens a fresh `ShopDirectoryPage` via `player.getPageManager().openCustomPage(lastRef, lastStore, dir)`. The native `$C.@BackButton` macro from `Common.ui` was intentionally NOT used because it auto-closes the current page instead of navigating - closing the browse page would dump the player back into the world, not the directory. The custom button with an `Activating` binding emitting `"Button"="back"` gives us the exact navigation we want.
- **Quantity Slider (`SliderNumberField`)** - Both the `ShopBrowsePage` confirm overlay (`#ConfirmQtySlider`) and the `ShopDirectoryPage` buy overlay (`#DirConfirmQtySlider`) now use a native `SliderNumberField` with `$C.@DefaultSliderStyle` + `$C.@DefaultInputFieldStyle` instead of the old `$C.@SmallSecondaryTextButton` minus/plus pair. Static range `Min: 1; Max: 64` in the `.ui` (Hytale does not let you mutate `Min`/`Max` at runtime); Java clamps the effective quantity to `getConfirmMaxQuantity()` / `getBuyConfirmMaxQuantity()` which consults the live stock. The big white label next to the slider keeps showing the current value in 16-18px bold so the number is readable while dragging.
- **`ShopBrowse.ui` - ConfirmQtySlider Row** - The old `Group { ... #ConfirmMinus ... #ConfirmQty Label ... #ConfirmPlus ... }` was replaced with a three-column row: `"Qty"` label (36x18, `#96a9be` 12px), `SliderNumberField #ConfirmQtySlider` with `FlexWeight: 1; Anchor: (Height: 5)` for the track, and a `#ConfirmQty` readout label (46x18, `#ffffff` bold 16px right-aligned). The slider's handle (16x16 per `@DefaultSliderStyle`) extends above/below the 5px track so the visual height is the same as the old button row.
- **`ShopDirectory.ui` - DirConfirmQtySlider Row** - Same swap for the directory buy overlay: `#DirConfirmMinus`/`#DirConfirmPlus`/`#DirConfirmQty` group replaced with a `"Qty"` label + `SliderNumberField #DirConfirmQtySlider` + `#DirConfirmQty` readout. The slider styling matches the browse one so both dialogs feel identical.
- **`ShopBrowsePage.java`** - `bindAllEvents` drops the two `Activating` bindings on `#ConfirmPlus`/`#ConfirmMinus`; replaces them with a single `ValueChanged` binding on `#ConfirmQtySlider` that emits `EventData.of("@ConfirmQty", "#ConfirmQtySlider.Value")`. New `Integer confirmQty` field on `ShopBrowseData` plus a matching `KeyedCodec<>("@ConfirmQty", Codec.INTEGER)`. New `handleConfirmQty(int)` method clamps to `[1, getConfirmMaxQuantity()]` and calls `refreshUI()`. The `plus`/`minus` cases in `handleConfirm` were deleted because the slider now drives the quantity directly. `buildConfirmOverlay` now pushes `#ConfirmQtySlider.Value` alongside the `#ConfirmQty.Text` readout.
- **`ShopDirectoryPage.java`** - Symmetric changes: new `Activating "Button"="back"`-free event flow (the directory already IS the directory, no back button needed), the two `Activating` bindings on `#DirConfirmPlus`/`#DirConfirmMinus` are gone, replaced with one `ValueChanged` on `#DirConfirmQtySlider`. New `Integer buyQty` field on `DirData`, new `handleBuyQty(int)` method with the same clamp logic. `buildBuyConfirmOverlay` pushes `#DirConfirmQtySlider.Value` + `#DirConfirmQty.Text`. The back button was only added to `ShopBrowsePage` - the directory is the landing page and has no parent to return to.
- **Back Button Routing Details** - `handleButton("back")` creates a fresh `ShopDirectoryPage(playerRef, player, plugin)` (no `onlyOwnerUuid`, so it reopens the full directory, not the /myshops filtered view). If `lastRef`/`lastStore` are null it sends an empty `sendUpdate` to avoid a hang. Exceptions are logged via `LOGGER.warning` and the page gracefully no-ops. The player's natural flow is: directory -> click shop card -> browse -> back -> directory, a clean round trip.
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL (10 deprecation warnings, all pre-existing).
- **Why Not `$C.@BackButton`** - Verified via `external_res/assets/Assets/Common/UI/Custom/Common.ui:832`: the `@BackButton` macro wraps a native `BackButton {}` widget that auto-closes the current page without firing any event. Several mods use it (achievements, rewards, titles, dialog editor, core shop page, ks-info-hub), but they all want the "close, don't navigate" semantic. For ks-shops the browse page's "back" should return to the directory, not dismiss the UI, so we needed a custom button with an `Activating` binding.
- **Pattern Reference** - `SliderNumberField` + `$C.@DefaultSliderStyle` + `$C.@DefaultInputFieldStyle` is a standard Hytale widget combo already used by `mods/achievements/.../AchievementSettings.ui` (position sliders), `mods/achievements/.../Admin/AdminPanel.ui` (many config sliders), `mods/info-hub/.../Admin/AdminPanel.ui` (items-per-page, search-length, etc), and `mods/quest-runeteria/.../QuestAdmin/QuestAdmin.ui` (daily/weekly counts, reroll cost). `InfoHubAdminPage.java:568` is the reference for how to wire the `ValueChanged` binding with the `"@Key"` codec field form.

### Items Tab: In-Place Buy Confirm Overlay (Fast-Buy, Colored Info Lines)

- **What** - A single click on any slot in the Shop Directory's items tab now opens an in-place buy-confirm dialog instead of navigating away to the seller's shop. The dialog shows item icon, item name (white), selling shop (cyan, bold), owner (lavender), price per unit (gold, bold), current stock (green), a quantity +/- selector, the running total (gold, bold), and three buttons: **BUY** (instant purchase via `ShopService.purchaseItemWithReason`), **VISIT SHOP** (navigate to `ShopBrowsePage` for the seller - same destination as the old click), and **CANCEL**. Reason we did not use Shift-click or right-click: `CustomUIEventBindingType` does not expose per-slot modifier-key events or a `SlotRightClicking`, so the cleanest discoverable path is a visible button triple.
- **`ShopDirectory.ui`** - New top-level `#DirConfirmOverlay` group (sibling of `#DirLayout`, inside `$C.@PageOverlay`) with a semi-transparent backdrop and a 400x380 `$C.@Container #DirConfirmDialog`. Inside the dialog: a 54x54 item-icon wrapper (`#DirConfirmIconBg` + `ItemIcon #DirConfirmIcon`), a column with `#DirConfirmItemName` (`#ffffff` bold 15px), `#DirConfirmShop` (`#4fc3f7` cyan bold 12px), `#DirConfirmOwner` (`#9fa8da` lavender 10px), a horizontal divider, `#DirConfirmPrice` (`#ffd700` gold bold 13px centered), `#DirConfirmStock` (`#81c784` green 11px centered), the quantity row (`#DirConfirmMinus` / `#DirConfirmQty` / `#DirConfirmPlus`, same `$C.@SmallSecondaryTextButton` pattern as ShopBrowse), `#DirConfirmTotal` (`#ffd700` gold bold 14px centered), and a bottom button row with `#DirConfirmBuy` (110px `$C.@TextButton`), `#DirConfirmVisit` (130px `$C.@TextButton`), `#DirConfirmCancel` (110px `$C.@CancelTextButton`).
- **`ShopDirectoryPage.java` - State** - Four new fields: `boolean buyConfirmActive`, `ShopData buyConfirmShop`, `ShopItem buyConfirmItem`, `int buyConfirmQuantity`. Populated on `handleItemSlotClick` instead of the old "open ShopBrowsePage immediately" path. Cleared in `clearBuyConfirm()` helper.
- **`ShopDirectoryPage.java` - Handler** - New `handleBuyAction(String)` method handles the five overlay button actions: `yes` purchases via `ShopService.purchaseItemWithReason`, sends a success/failure chat message (same colored-message pattern as `ShopBrowsePage.handleConfirm`), clears the overlay, and calls `executeQuery()` so the items grid reflects any stock decrement; `visit` snapshots the shop, clears the overlay, and opens `ShopBrowsePage`; `cancel` just clears; `plus`/`minus` bump the quantity, capped by `getBuyConfirmMaxQuantity()` (unlimited stock = 64 max, limited = remaining stock).
- **`ShopDirectoryPage.java` - Build** - New `buildBuyConfirmOverlay(ui, i18n)` method driven by the `buyConfirmActive` flag. Sets the overlay's `Visible`, pushes the icon / name / shop / owner / price / stock / qty / total / button labels. Called unconditionally from `buildUI` after pagination so the overlay state is always in sync with the Java model.
- **`ShopDirectoryPage.java` - Events + Codec** - Five new `Activating` bindings on `#DirConfirmBuy`/`#DirConfirmVisit`/`#DirConfirmCancel`/`#DirConfirmPlus`/`#DirConfirmMinus` all emit `EventData.of("BuyAction", "yes|visit|cancel|plus|minus")`. `DirData` gets a new `String buyAction` codec field + `handleDataEvent` dispatch.
- **Imports** - `com.hypixel.hytale.server.core.Message` for the success/failure chat messages, `com.kyuubisoft.shops.service.ShopService` for the `PurchaseResult` typed return. Same import set `ShopBrowsePage` uses for the same job.
- **i18n v19 -> v20** - Eight new keys: `shop.directory.buy.title` (`"CONFIRM PURCHASE"`), `price_each` (`"{0} Gold each"`), `stock` (`"{0} in stock"`), `stock_unlimited` (`"Unlimited stock"`), `total` (`"Total: {0} Gold"`), `confirm` (`"BUY"`), `visit` (`"VISIT SHOP"`), `cancel` (`"CANCEL"`). The tooltip's `click_hint` line was updated from `"Click to visit this shop"` to `"Left click to buy | Right buttons to visit the shop"` so the new flow is discoverable from the hover tooltip itself. `CURRENT_LOCALIZATION_VERSION` bumped to 20.
- **Colored Info Lines** - Requested by the user: every info line in the dialog is a distinct semantic color so you can read the shop/owner/price/stock at a glance. Shop = `#4fc3f7` (Material cyan), Owner = `#9fa8da` (Material lavender), Price = `#ffd700` (gold), Stock = `#81c784` (Material green), Total = `#ffd700` (gold). The item name stays white (`#ffffff`) so it reads as the primary title.
- **Why not Shift-Click** - `CustomUIEventBindingType` was verified via `javap`: it offers `SlotClicking`, `SlotDoubleClicking`, `SlotMouseEntered`, `SlotMouseExited` on slots, but no per-slot modifier-key or right-click variant. Adding the `VISIT SHOP` button is the closest-to-native way to expose both actions without a hidden keyboard shortcut players would never discover.
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL (18 deprecation warnings, all pre-existing).

### Item Search Grid: Per-Slot Shop/Owner Tooltip

- **What** - Hovering an item in the Shop Directory's Items tab now shows which shop + owner sells it, along with the price, stock, and a "Click to visit this shop" hint. Before, the native ItemGrid tooltip only showed the bare item name and there was no way to tell the 45 slots apart beyond their icon - defeating the purpose of a cross-shop item search.
- **How** - `ItemGridSlot` exposes `setName(String)` and `setDescription(String)` which override the tooltip text on a per-slot basis (verified via `javap -cp libs/HytaleServer.jar com.hypixel.hytale.server.core.ui.ItemGridSlot`). `populateItemSearchGrid` now builds a multi-line description for every populated slot: `Shop: <name>` / `by <owner>` (or `Admin Shop` for admin listings) / `Price: <n> Gold` / `Stock: <n>` or `Stock: Unlimited` / `Click to visit this shop`. The slot name is set to `ShopBrowsePage.formatItemName(itemId)` so the header line shows a readable item name regardless of whether the item's native display name is set.
- **i18n v18 -> v19** - Six new keys under the `shop.directory.tooltip.*` namespace: `shop` (format: `"Shop: {0}"`), `owner` (`"by {0}"`), `price` (`"Price: {0} Gold"`), `stock` (`"Stock: {0}"`), `unlimited` (`"Stock: Unlimited"`), `click_hint` (`"Click to visit this shop"`). `CURRENT_LOCALIZATION_VERSION` bumped to 19 so the autoupdate backup + regenerate flow picks up the new keys for every language file on next load. Other languages fall back to en-US for the new keys until translators fill them in (same fallback path all previous additions used).
- **Native Item Metadata Preserved** - The tooltip override only replaces the name/description lines - the client still renders the item's native enchantment / stats / DTT lines below the custom text because the underlying `ItemStack` still carries the full BSON metadata from `BsonMetadataCodec.decode(item.getItemMetadata())`. Enchanted listings show as enchanted + named + shop-info all in one tooltip.
- **Click Flow Unchanged** - Clicking a slot still routes through `handleItemSlotClick(Integer)` which opens `ShopBrowsePage` for the seller shop. The new `click_hint` line in the description just makes the click affordance visible on hover.
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL. `formatItemName` on `ShopBrowsePage` is already package-private static so no visibility change was needed for the cross-file call.
- **Pattern Reference** - `ItemGridSlot.setName` / `setDescription` are standard native Hytale slot-level overrides - they are the Hytale-native equivalent of Minecraft's `display.Name` / `display.Lore` NBT. The shops plugin is the first mod in this codebase to use them; all other mods (bank, graveyard, item-control, pets) either do not set per-slot tooltip text at all or rely on the item's native metadata.

### Shop Directory Items Tab: Native ItemGrid (45 slots, click to open shop)

- **What Changed** - The Items-mode view in the Shop Directory was rebuilt to use the same native 9x5 `ItemGrid` that the Browse / purchase UI uses, replacing the 12 custom cards. Per-page capacity jumps from 12 to 45 items, hover tooltips now use Hytale's native item renderer (shows full BSON metadata - enchantments, custom stats, pet ids, weapon mastery), and visitors can click any slot to jump straight into that item's shop via `ShopBrowsePage`.
- **`ShopDirectory.ui`** - The 12 hand-rolled `#ICard0..#ICard11` blocks are gone. In their place: a new local macro `@SearchSlotStyle = ItemGridStyle(SlotSpacing: 4, SlotSize: 48, SlotIconSize: 40, SlotBackground: "../../Pages/Bank/BankSlot.png")` (mirrors `@BrowseSlotStyle` from `ShopBrowse.ui` so the two grids visually match), plus a single `ItemGrid #ItemSearchGrid { Anchor: (Width: 488, Height: 260); SlotsPerRow: 9; Style: @SearchSlotStyle; }` inside the existing `Group #ItemGrid` wrapper. The `LayoutMode: CenterMiddle` on the wrapper keeps the grid centred in the 960-wide container.
- **`ShopDirectoryPage.java` - Slot Population** - New `populateItemSearchGrid(ui)` method replaces the old `buildItemCards` / 12-card loop. It builds a `List<ItemGridSlot>` from the current page of `itemResults`, attaches the persisted BSON metadata via `BsonMetadataCodec.decode(...)` so enchanted listings render correctly, and pushes the list via `ui.set("#ItemSearchGrid.Slots", slots)`. Every slot is `setActivatable(true)` (pitfall #118 - required for `SlotClicking` to fire even with drag disabled). Unlimited-stock items show a quantity of 1; limited-stock items show their remaining stock as the native slot-quantity badge (consistent with how BUY mode works in `ShopBrowsePage`). Grid flags: `AreItemsDraggable=false`, `DisplayItemQuantity=true`.
- **`ShopDirectoryPage.java` - Click Routing** - The 12 per-card `Activating` bindings on `#IBtn0..#IBtn11` were replaced by a single `CustomUIEventBindingType.SlotClicking` binding on `#ItemSearchGrid` that emits `EventData.of("ItemSlot", "")`. Hytale auto-populates `SlotIndex` into the new `DirData.itemSlot` (Integer, was `itemCard` String) via a `KeyedCodec<>("SlotIndex", Codec.INTEGER)` codec field. `handleItemCardClick(String)` became `handleItemSlotClick(Integer)` - the routing logic is otherwise identical (clicking an item opens `ShopBrowsePage` for the shop that sells it).
- **Page Size Constant** - `ITEM_CARDS_PER_PAGE = 12` → `ITEMS_PER_PAGE = 45`. Pagination in `executeItemQuery`, `buildPagination`, and `getMaxPage` automatically picks up the new size because they all read the same constant.
- **Imports Added** - `ItemStack`, `ItemGridSlot`, `BsonDocument`, `BsonMetadataCodec` - the same set used by `ShopBrowsePage.populateBrowseGrid`.
- **UX Improvement** - Clicking a slot opens the seller's `ShopBrowsePage` (unchanged behaviour from the old card click). The difference: you now see 45 items at a glance instead of 12, and hovering shows the full native item tooltip with metadata instead of the hand-built card text. For a "search items across all shops" workflow this is a much bigger improvement than the 220x155 card redesign was.
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL. Only the pre-existing `getTransformComponent()` deprecation warnings.
- **Pattern Reference** - `mods/shops/src/main/java/com/kyuubisoft/shops/ui/ShopBrowsePage.java:527` (`populateBrowseGrid` method) is the direct template this was cloned from. Same `@SearchSlotStyle` dimensions, same `List<ItemGridSlot>` build loop, same `setActivatable(true)` handling, same `SlotClicking` + `SlotIndex` codec wiring.

### Shop Directory Icons: Fix Sizing + Items Tab Design

- **Root Cause of the Blank Icon** - Even after the inline-cards refactor, `ItemIcon #DAvatarN { Anchor: (Full: 0); }` rendered as a blank square. The `Full: 0` anchor inside a `LayoutMode: CenterMiddle` wrapper group collapses to 0x0 because CenterMiddle does not give "fill" semantics to its children - it centres them at their natural size, which is zero for an ItemIcon without explicit `Width` / `Height`. Bank's `#H0Icon { Anchor: (Width: 32, Height: 32); }` and Graveyard's `#Ic0 { Anchor: (Width: 44, Height: 44); }` both use the explicit-size form.
- **Fix - `ShopDirectory.ui`** - All 8 shop-card avatars now declare `ItemIcon #DAvatarN { Anchor: (Width: 44, Height: 44); ItemId: "Ingredient_Bar_Gold"; }` (explicit 44x44 inside a 48x48 background + 2px padding wrapper). All 12 item-card icons now declare `ItemIcon #IIconN { Anchor: (Width: 44, Height: 44); ItemId: "Ingredient_Bar_Gold"; }`. The default `ItemId` is a neutral gold-bar placeholder so a card briefly renders *something* if Java hasn't pushed an override yet - this also makes broken selector issues immediately visible (a blank square would previously look like "the icon logic is off" when it was really just a sizing problem).
- **Fix - `ShopDirectoryPage.buildShopCards`** - The Java fallback path now always pushes an icon id even when `shop.getDisplayIconItemId()` returns null (empty shop, no explicit icon picked). It defaults to `Ingredient_Bar_Gold` instead of skipping the `ui.set` call, so empty shops in the directory show a gold-bar placeholder rather than a blank square.
- **Items Tab Redesign** - Item-search cards were bumped from 220x110 to the same 220x155 dimensions as shop cards. The internal structure now mirrors the shop-card layout: 48x48 icon + name/price/stock top row, horizontal divider, bolder shop name, owner line. Font sizes were bumped (name 13, price 12, stock 10, shop name 11, owner 10) because the previous 9-10px stack looked cramped. Visual weight between the Shops and Items tab is now identical, so switching tabs no longer causes a layout jump. `#ItemGrid` block was regenerated via a small Python script (see commit message) since the 12 cards all share the same structure.
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL. The brace count for the regenerated item cards was sanity-checked (4 nested closes per card, matching the shop-card structure).
- **Pattern Reference** - `mods/bank/src/main/resources/Common/UI/Custom/Pages/Bank/BankPage.ui:229` (inline `#H0Icon { Anchor: (Width: 32, Height: 32); }`) and `mods/graveyard/src/main/resources/Common/UI/Custom/Pages/Graveyard/GraveyardPage.ui:59` (inline `#Ic0 { Anchor: (Width: 44, Height: 44); }`). Both explicit-size ItemIcons in inlined cards and both production-tested. The `Full: 0` form only works in `LayoutMode: Full` or on the root of a container - not inside `CenterMiddle` or `LeftCenterWrap`.

### Shop NPCs: Look-at-Player Rotation

- **What** - Shop NPCs (both admin and player shops) now continuously face the nearest player in the same world. Each player sees the NPC looking at THEM individually, because rotation is sent as a per-player packet rather than mutated shared world state.
- **Implementation** - New `ShopNpcRotationManager` (`mods/shops/src/main/java/com/kyuubisoft/shops/npc/ShopNpcRotationManager.java`) mirrors the proven Core `CitizenRotationManager` pattern: cached NetworkId + cached spawn position + `EntityUpdates` packet with a `TransformUpdate(ModelTransform(lookOrientation, bodyOrientation))`. No `world.execute()` is needed - the tick reads only cached data + `playerRef.getTransform().getPosition()` (thread-safe) and writes via `playerRef.getPacketHandler().write(packet)` (thread-safe).
- **NetworkId Caching** - `ShopNpcManager.cacheRotationTarget(shopId, entityRef, store, position)` is called right after `NPCPlugin.spawnEntity` (both `spawnNpcInternal` and `spawnNpcInternalAt` paths) inside the same `world.execute()` context. It reads `NetworkId` from the freshly committed entity and registers the shop with the rotation manager. Gated by `config.npc.lookAtPlayer` - flip the flag to disable the feature.
- **Cleanup** - `cleanupTracking` now calls `rotationManager.untrackNpc(shopId)`, so pickup / delete / respawn stops rotating the old shop immediately. `despawnAll` clears the whole rotation registry on shutdown, matching the other tracking maps.
- **Scheduler Tick** - `ShopPlugin` now holds a `Map<UUID, Player> onlinePlayers` populated from `PlayerConnectEvent.getPlayer()` / cleared on `PlayerDisconnectEvent`. A new `scheduleAtFixedRate(..., 1000, 100, TimeUnit.MILLISECONDS)` entry calls `npcManager.getRotationManager().tick(onlinePlayers.values())` every 100 ms (same cadence Core uses for citizens). Only registered when `config.npc.lookAtPlayer == true`, short-circuits when `onlinePlayers` is empty, and fails silent via `LOGGER.fine` so a broken tick never spams the console.
- **Distance + Threshold Filters** - Rotation packets are only sent for players within 25 blocks of the shop and only when the target yaw/pitch moves more than 0.015 rad since the last packet to that player. Idle shops with an unmoving player generate zero packets after the first frame.
- **Config** - `config.npc.lookAtPlayer` already existed (`ShopConfig.java:259`, default `true`) but was previously dead code - it is now actually wired up. Ops can flip it off in `config.json` to disable the feature without restarting; the tick honours the startup value (flip + reload requires a restart).
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL. Only pre-existing `getTransformComponent()` / `getPlayerRef()` / `getUuid()` deprecation warnings, none from the rotation code.
- **Pattern Reference** - `mods/core/src/main/java/com/kyuubisoft/core/citizen/CitizenRotationManager.java` is the battle-tested original this was cloned from. Key facts preserved: (1) packet-based rotation means zero ECS mutation on the tick thread, (2) per-player direction cache throttles the common "player stands still" case, (3) NetworkId must be cached at spawn because reading it per-tick would require world.execute.

### Shop Directory: Inline Cards with Unique IDs (Icon + Layout Fix)

- **Problem** - The Shop Directory's shop cards and item-search cards still showed blank avatars (no icon next to the shop name) and the Items tab rendered with visibly wrong proportions for the user. Previous attempts to fix this by swapping `ItemSlot` -> `ItemIcon` and dropping 3-level selectors down to 2-level (`#DCardN #DAvatar.ItemId`) did not land reliably - macro-instanced child widgets silently fail to receive dynamic `ItemId` updates even with the 2-level form, while their sibling labels appeared to update correctly.
- **Root Cause** - The directory used `@DirCard` / `@ItemResultCard` macros with shared inner IDs (`#DAvatar`, `#IIcon`, `#DName`, ...). Hytale's UI selector resolution against macro-instantiated descendants is inconsistent for the `ItemIcon.ItemId` property specifically. The Pets plugin (`PetCollection.ui`) and Bank plugin (`BankPage.ui`) side-step this entirely by inlining each card with uniquely-named child widgets (`#Entry0` / `#EntryName0`, `#H0Icon` / `#H1Icon`), letting Java address each widget via a flat 1-level selector.
- **Fix** - `ShopDirectory.ui` - Removed the `@DirCard` and `@ItemResultCard` macro definitions. Inlined all 8 shop cards (`#DCard0`..`#DCard7`) and all 12 item cards (`#ICard0`..`#ICard11`) directly inside `#ShopGrid` / `#ItemGrid`, with every dynamic child widget given a unique per-card suffix: `#DAvatar0`..`#DAvatar7`, `#DName0`..`#DName7`, `#DOwner0`..`#DOwner7`, `#DStatus0`..`#DStatus7`, `#DRating0`..`#DRating7`, `#DItems0`..`#DItems7`, `#DDistance0`..`#DDistance7`, `#DCategory0`..`#DCategory7`, `#DBtn0`..`#DBtn7` for the shop grid, and `#IIcon0`..`#IIcon11`, `#IName0`..`#IName11`, `#IPrice0`..`#IPrice11`, `#IStock0`..`#IStock11`, `#IShop0`..`#IShop11`, `#IOwner0`..`#IOwner11`, `#IBtn0`..`#IBtn11` for the item grid. The outer card sizes (220x155 for shops, 220x110 for items) and the `@DirCardBtnStyle` / `@TabBtnActiveStyle` / `@TabBtnInactiveStyle` macros are unchanged, so the visual layout is identical to the macro version.
- **Fix** - `ShopDirectoryPage.java` - `buildShopCards` and `buildItemCards` now address widgets with flat 1-level selectors (`#DAvatar` + `i`, `#IIcon` + `i`, ...) instead of the previous 2-level form (`#DCardN #DAvatar`, `#ICardN #IIcon`). The card click event bindings were also flattened to `#DBtn` + `i` and `#IBtn` + `i`. Visibility toggling still uses the outer `#DCardN.Visible` / `#ICardN.Visible` - those 1-level selectors already worked because the outer card group is a direct child of `#ShopGrid` / `#ItemGrid`.
- **Verification** - `./gradlew :mods:shops:build` - BUILD SUCCESSFUL (4 deprecation warnings only, all pre-existing `getTransformComponent()` calls).
- **Pattern Reference** - `mods/pets/src/main/resources/Common/UI/Custom/Pages/Pets/PetCollection.ui` (inline `#Entry0`..`#Entry5` pattern) and `mods/bank/src/main/resources/Common/UI/Custom/Pages/Bank/BankPage.ui` (inline `#H0Icon`..`#H10Icon` pattern). Both are production-tested and work reliably for dynamic icon updates.

### Full ItemStack Metadata Tracking Through the Shop Pipeline

- **Problem** - Shops previously only persisted the bare item id. Any ItemStack-level BSON metadata (enchantments, weapon mastery levels, pet ids, custom stats, durability snapshots, mod-specific flags) was silently dropped the instant the owner dropped the item into the shop grid. When a buyer purchased the item they received a vanilla copy with no metadata - which wiped enchants, reset weapon mastery levels, broke stored pet ids, and erased every mod's item-specific state.
- **New `BsonMetadataCodec` Helper** - `mods/shops/src/main/java/com/kyuubisoft/shops/util/BsonMetadataCodec.java` wraps `BsonDocument.toJson()` / `BsonDocument.parse()` for round-trip persistence into the existing `item_metadata TEXT` SQL columns. Null-tolerant (empty / unparseable inputs return null so legacy rows fall back to vanilla ItemStacks cleanly). Also provides a `stripDttSuffix(String)` helper that reduces any DTT virtual item id to its canonical form before persistence - virtual ids carry runtime cache state in the id itself, so only the canonical id must land in the database.
- **Capture on Save (`ShopEditPage.handleSave`)** - Building `ShopItem` instances from the staging container now reads `stack.getMetadata()` and encodes it via `BsonMetadataCodec.encode(...)`. The canonical item id is written via `stripDttSuffix(...)`. Legacy rows (no metadata) still save null - `encode` short-circuits on empty docs.
- **Restore on Edit (`ShopEditPage.populateStagingFromShop`)** - When the owner reopens the editor, `BsonMetadataCodec.decode(item.getItemMetadata())` rebuilds the original BsonDocument and the staging grid is populated with `new ItemStack(itemId, quantity, meta)` when metadata exists, or the vanilla constructor otherwise. Enchanted listings now display as enchanted in the editor.
- **Restore on Purchase (`ShopService.giveItem(..., String metadataJson)`)** - New 4-arg overload decodes the stored metadata and uses the `(String, int, BsonDocument)` ItemStack constructor, preserving enchantments / custom stats / pet ids across delivery. The existing 3-arg `giveItem(PlayerRef, String, int)` overload is kept and delegates to the new one with `metadataJson = null` so no caller was broken.
- **`MailboxEntry` Carries Metadata** - New `itemMetadata` field (nullable String, serialised BSON JSON). New `itemMail(... , String itemMetadata)` factory variant and new `fromDatabase(..., String itemMetadata, boolean claimed)` factory. The old 6-arg and 10-arg factories are kept as delegators so no caller breaks.
- **`MailboxService.createItemMail` Overload** - New `createItemMail(ownerUuid, shopId, shopName, buyerName, itemId, quantity, String itemMetadata)` overload; the existing 6-arg overload delegates with `itemMetadata = null` (backward compat).
- **`ShopDatabase` Round-Trip** - `shop_mailbox` gained an `item_metadata TEXT` column in both the MySQL and SQLite `CREATE TABLE` paths. Idempotent `ALTER TABLE shop_items ADD COLUMN item_metadata TEXT` and `ALTER TABLE shop_mailbox ADD COLUMN item_metadata TEXT` migrations run on startup (wrapped in try/catch - fails cleanly on already-migrated databases). `insertMailboxEntry` now writes the 11th parameter, `readMailboxEntry` reads the new column inside a try/catch so legacy rows without the column still load (returning null metadata = vanilla item on claim). `readShopItem` also wraps its `getString("item_metadata")` read in try/catch for the same defensiveness.
- **`ShopService.purchaseItem`** - Step 3a `createItemMail` call now passes `shopItem.getItemMetadata()` so the ITEM mail stored for the buyer carries the same BSON blob the owner listed.
- **`ShopService.pickupShop`** - Step 1 `createItemMail` loop passes `item.getItemMetadata()` for every refunded stock batch, so an owner who packs up their shop gets back the exact same enchanted items they listed.
- **`MailboxPage.dispenseAndClaim`** - ITEM claim path now calls `giveItem(playerRef, mail.getItemId(), mail.getQuantity(), mail.getItemMetadata())` so the buyer receives the stored metadata on claim.
- **`ShopBrowsePage.populateBrowseGrid`** - Each `ItemGridSlot` is now built with a full `new ItemStack(itemId, quantityToShow, meta)` instead of the vanilla 2-arg constructor when the listing carries metadata, so Hytale's native item tooltip + DTT render the item's full enchants / stats / custom state on hover in the browse grid. Legacy listings (null metadata) fall back to the existing vanilla constructor.
- **`#ConfirmIcon` Tooltip** - Unchanged. The `ItemSlot` native widget only exposes an `.ItemId` property (not `.ItemStack`), so the confirmation overlay icon shows a vanilla render of the item. This is called out as a known limitation - the actual delivered ItemStack on purchase still carries the full metadata.
- **Legacy Compat** - Legacy rows in `shop_items` and `shop_mailbox` with `item_metadata = NULL` continue to load cleanly (decode returns null, ItemStack is constructed without metadata). The migration is idempotent - running it against a DB that already has the column is a no-op.
- **Pitfalls Avoided** - No public API signatures on `ShopService.purchaseItem` / `sellItem` / `pickupShop` were changed (the plan's guardrail). `giveItem` got an overload, not a replacement, so existing callers work without edits. BSON decoding is wrapped in try/catch everywhere so a corrupted metadata blob downgrades gracefully to a vanilla item instead of crashing the purchase / claim path.

### Verification

- `./gradlew :mods:shops:clean :mods:shops:build` - BUILD SUCCESSFUL
- `BsonMetadataCodec.class`, updated `ShopItem.class`, `MailboxEntry.class`, `ShopService.class` all present in the final `KyuubiSoftShops-1.0.0.jar` (jar tf verified).

### Browse Page: Native ItemGrid Layout (Click to Buy)

- **`ShopBrowsePage` Switched to Native `ItemGrid`** - The 3x3 custom card grid (`@ShopCard #Card0..#Card8`) is gone. The buyer view now uses a single native `ItemGrid #BrowseGrid` (9 columns x 5 rows = 45 slots) that matches the look of the Bank, Inventory, and `ShopEditPage` grids. Clicking a slot opens the existing `#ConfirmOverlay` dialog with the item pre-loaded and the same `#ConfirmMinus` / `#ConfirmPlus` / `#ConfirmQty` / `#ConfirmYes` / `#ConfirmNo` wiring that drove the legacy card buttons. Buy/Sell tab bar, info bar, empty state, pagination, and confirmation overlay are unchanged.
- **`ITEMS_PER_PAGE` Bumped to 45** - One screen of items (9x5) now fits per page, so most shops will not paginate at all. Pagination still works if a shop has more than 45 buyable or sellable items.
- **Display-Only Grid, No Drag & Drop** - `ui.set("#BrowseGrid.AreItemsDraggable", false)` keeps visitors from picking slots up. `DisplayItemQuantity` is on so the stock count shows as the native slot quantity badge in BUY mode. No `InventorySectionId` is bound because there is no backing `ContainerWindow` - the grid is driven purely by `ui.set("#BrowseGrid.Slots", List<ItemGridSlot>)`. Each `ItemGridSlot` has `setActivatable(true)` so `SlotClicking` events fire even though drag is disabled.
- **`populateBrowseGrid()` Method** - Replaces the old `buildCards()` method. Builds a `List<ItemGridSlot>` from the active `buyItems` / `sellItems` list, with `ItemStack` quantities reflecting shop stock in BUY mode, and pushes it to the client. Per-card overlays (out-of-stock, can't-afford, shop-full, shop-out-of-funds) and per-card tooltips were dropped in this first pass - the confirmation dialog already shows price, total, and availability when the slot is clicked, and Hytale's native item tooltip shows the item name on hover.
- **`SlotClicking` Event Binding** - A single `CustomUIEventBindingType.SlotClicking` binding on `#BrowseGrid` emits `Action=browse_click`. `SlotIndex` is auto-populated by Hytale into `ShopBrowseData.slotIndex` via a new `KeyedCodec<>("SlotIndex", Codec.INTEGER)` field on the codec. `handleBrowseSlotClick(Integer slotIndex)` routes to `handleBuyClick(int actualIndex)` or `handleSellClick(int actualIndex)` depending on `mode`, which set `confirmActive = true` + `confirmItem` and trigger a `refreshUI()` to show the `#ConfirmOverlay`. The 9 legacy `#Card0..#Card8 #CardBtn` Activating bindings are gone.
- **`ShopBrowseData` Codec Change** - Dropped the `Buy` string field (was used by legacy card clicks). Added `Action` (string) and `SlotIndex` (integer) fields matching the pattern used by `ShopEditPage`'s `EditData`.
- **`ShopBrowse.ui`** - Added a local `@BrowseSlotStyle = ItemGridStyle(SlotSpacing: 4, SlotSize: 48, SlotIconSize: 40, SlotBackground: "../../Pages/Bank/BankSlot.png")` macro (same `BankSlot.png` path as `ShopEdit.ui`'s `@ShopSlotStyle`, known-working). `Group #CardGrid` + 9 `@ShopCard` instances replaced by `ItemGrid #BrowseGrid { Anchor: (Width: 488, Height: 260); SlotsPerRow: 9; Style: @BrowseSlotStyle; Background: #1a2332(0); Padding: 0; }`. `#CardContainer` switched from `LayoutMode: TopScrolling` to `LayoutMode: CenterMiddle` since the grid is a fixed 9x5 that fits the panel without scrolling. The `@ShopCard` macro definition itself is kept in the file (unused) so external references do not break; the `@CardButtonStyle` macro is also kept for the same reason. `#ConfirmOverlay` is untouched.
- **Pitfalls Avoided** - `.ui` is pure ASCII (verified). `InventorySectionId` intentionally NOT set (would bind to a non-existent container without a `ContainerWindow`). `setActivatable(true)` set on every slot (required for `SlotClicking` events to fire per pitfall #118). Flat selectors used throughout - no 3-level nested selectors on the grid. `SlotBackground` reuses the same asset path as `ShopEdit.ui` (known-working). The old `@ShopCard` + `@CardButtonStyle` macros are kept in the file to avoid "undefined macro" errors from any stale reference, but are no longer instantiated.

### NPC-Only Shop Creation (Shop_Block Removal)

- **New `Shop_NPC_Token` Item (Shop Business License)** — A non-block usable item that replaces the six legacy `Shop_Block*` variants. Recipe at Carpentrybench: 8x Ingredient_Plank_Oak + 2x Ingredient_Bar_Iron + 2x Ingredient_Bar_Gold + 1x Ingredient_Leather_Medium. `MaxStack: 1`, `DropOnDeath: false`, `Quality: Rare`. Icon is currently a placeholder copy of `shop_block.png` (will be replaced once the licensed art is ready).
- **New `shop_npc_token_use` Interaction Codec** — `ShopNpcTokenInteraction extends SimpleInstantInteraction` (same pattern as `PetEggInteraction` / `MountHornInteraction`). Right-click flow: showcase guard -> permission check (`ks.shop.user.create`) -> shop count vs `maxShopsPerPlayer` -> fetch player position + yaw -> `ShopService.createPlayerShop(...)` -> register in world index -> `world.execute(() -> ShopNpcManager.spawnNpcAtPosition(shop, world, pos, rotY))` -> consume one token from hotbar (or storage fallback). Default shop name is built from the owner's username + suffix.
- **`ShopNpcManager.spawnNpcAtPosition(shop, world, position, rotY)`** — New public method that spawns a shop NPC at exact world coordinates, bypassing the old block-offset math. Persists the new coords on the `ShopData` and registers the shop in the world index so subsequent lazy-spawn events find it. The internal helper `spawnNpcInternalAt` mirrors `spawnNpcInternal` but takes a raw position + rotation, skipping `calculateNpcPosition` which only makes sense when anchored to a block.
- **`ShopNpcManager.respawnNpc(ShopData, World)`** — New overload that despawns the current NPC entity on the world thread and respawns it at the stored `posX/Y/Z` with the latest skin. Used by the Settings-panel "Apply Skin" button. The legacy `respawnNpc(ShopData)` remains as a tracking-only cleanup for callers without a World reference.
- **`ShopService.migrateShopBlocksToNpcShops()`** — One-shot startup migration that clears stale `npcEntityId` values on every existing player shop so the next world-init event re-spawns them via the standard lazy path. Guarded by a `.shops_npc_migration_done` marker file in the plugin data folder. Logs `Migrated N shops to standalone NPCs.` via the new `shop.migration.npc_migrated` i18n key.
- **Shop_Block JSON Variants Deleted** — `Shop_Block.json`, `Shop_Block_Birch.json`, `Shop_Block_Dark.json`, `Shop_Block_Mossy.json`, `Shop_Block_Red.json`, `Shop_Block_Stone.json`. The block-use codec registration for `shop_block_use` has been removed from `ShopPlugin.init()`. `ShopBlockBlockInteraction.java` is kept on disk (unused) so any legacy block still placed in an existing world logs a handled warning instead of crashing the server. The shared model/texture/animation assets in `Common/Blocks/shop/` remain untouched because `Mailbox_Block.json` still uses them.
- **`/ksshop create` Fallback Kept** — The command still works as an admin/debug fallback. It now spawns the NPC directly at the player's feet (via `spawnNpcAtPosition`) instead of scanning for a nearby Shop_Block. The `findNearbyShopBlock` helper and its `BlockType`/`Vector3i` imports were removed. The `shop.create.no_block_nearby` i18n key is kept in `en-US.json` for rollback, but is no longer referenced from Java.
- **NPC Skin Picker in Editor Settings Panel** — New "SHOP NPC" section in `ShopEdit.ui` with a `#NpcSkinField` text input (placeholder: "Minecraft-style username", `MaxLength: 32`), an `#ApplySkinBtn` that commits + persists + calls `ShopNpcManager.respawnNpc(shop, world)`, and a `#NpcSkinStatusLabel` that reports the current binding. `ShopEditPage` gained an `editedNpcSkin` field initialised from `shopData.getNpcSkinUsername()`, a `ValueChanged` binding for `#NpcSkinField`, an `Activating` binding for `#ApplySkinBtn`, a new `npcSkin` case in the `Field` switch, and a new `handleApplySkin()` method. The `EditData` codec gained an `@NpcSkin` field + `npcSkinVal` slot.
- **i18n v12** — Bumped `_localization_version` from `11` to `12` and `CURRENT_LOCALIZATION_VERSION` in `ShopI18n.java`. Added keys: `shop.migration.npc_migrated`, `shop.token.name`, `shop.token.description`, `shop.token.spawned`, `shop.token.not_enough_shops`, `shop.token.failed`, `shop.edit.label.skin`, `shop.edit.skin.placeholder`, `shop.edit.skin.apply`, `shop.edit.skin.applied`. Rewrote `shop.help.gs.step1` and `shop.help.gs.step2` to describe the NPC-only flow (craft the license, right-click to spawn NPC) instead of the Shop_Block placement flow.
- **Pitfalls Avoided** — New `.ui` content is pure ASCII. No `ShrinkTextToFit`, `MinShrinkTextToFitFontSize`, `LetterSpacing`, `WrapText`, or `VerticalAlignment: Top`. No new macro definitions at the root of `ShopEdit.ui` — the skin section is inlined. Token interaction runs on the entity thread and dispatches the spawn via `world.execute()` per the pets reference pattern.

### Shop Icon Picker

- **Curated 6x4 Icon Grid in Editor Settings** — The Settings panel of `ShopEditPage` now ships a 24-tile picker (`#IconPick0` through `#IconPick23`) under a new `SHOP ICON` header. Each tile is a 44x44 button that wraps an `ItemIcon` with a baked-in vanilla item. Layout is `LeftCenterWrap` so the tiles flow into 6 columns x 4 rows. Owners click any tile to bind that icon to their shop; clicking the active tile clears it back to the first-item fallback. A subtle gold overlay (`#IconPickXSelector`) marks the current selection and a `#IconPickSelectedLabel` reports the chosen icon name (or `(first item fallback)`).
- **24 Curated Vanilla Items** — Verified against `external_res/dev-export/items.json`. Order matches the .ui IDs in `ICON_OPTIONS[]`: Weapon_Sword_Iron, Weapon_Axe_Iron, Weapon_Spear_Iron, Weapon_Shield_Iron, Weapon_Crossbow_Iron, Weapon_Battleaxe_Iron, Armor_Iron_Head, Armor_Iron_Chest, Armor_Iron_Hands, Armor_Iron_Legs, Tool_Pickaxe_Iron, Tool_Hatchet_Iron, Tool_Hammer_Iron, Ingredient_Bar_Iron, Ingredient_Bar_Gold, Ingredient_Bar_Silver, Ingredient_Crystal_Blue, Ingredient_Crystal_Red, Ingredient_Crystal_Green, Food_Bread, Food_Cheese, Plant_Fruit_Apple, Ingredient_Leather_Medium, Ingredient_Feathers_Light.
- **Persistent `iconItemId` Field on `ShopData`** — New nullable field (with getter/setter) plus `getDisplayIconItemId()` helper that returns the explicit choice when set or falls back to `items.get(0).getItemId()` (or `null` for empty shops). The new field flows through `ShopData.fromDatabase(...)`.
- **DB Schema and Migration** — `shop_shops` gains an `icon_item_id` column (`VARCHAR(128)` for MySQL, `TEXT` for SQLite). The `createTables()` migration block adds an idempotent `ALTER TABLE shop_shops ADD COLUMN icon_item_id ...` wrapped in try/catch (matching the existing `shop_balance` migration pattern). `saveShop()` and `readShop()` were extended to round-trip the field; `readShop()` reads the column inside its own try/catch so legacy databases without the column still load cleanly.
- **`ShopEditPage` Wiring** — New `editedIconItemId` field initialized from `shopData.getIconItemId()` in the constructor. `bindAllEvents()` registers 24 `Activating` event bindings (`#IconPick0..#IconPick23` -> `EventData.of("IconPick", String.valueOf(i))`). `EditData.CODEC` gained an `IconPick` keyed codec entry. `handleDataEvent()` routes the event to the new `handleIconPick()` which validates the index, toggles `editedIconItemId`, marks `dirty = true`, and refreshes the UI. `buildSettingsPanel()` now drives the per-tile selector visibility and updates `#IconPickSelectedLabel.Text`. `handleSave()` calls `shopData.setIconItemId(editedIconItemId)` so the choice persists across the existing save flow.
- **`ShopDirectoryPage.buildCards()`** — Avatars now read `shop.getDisplayIconItemId()` instead of always grabbing the first item, so empty shops or shops with a custom icon render correctly. Removed the now-unused `ShopItem` import and inlined the item-count read.
- **`ShopBrowsePage` Header Avatar** — `ShopBrowse.ui` gained a new `#ShopAvatar` `ItemIcon` in the title bar (replacing the placeholder spacer at `Width: 120`). `ShopBrowsePage.build()` populates it from `shopData.getDisplayIconItemId()` with the same fallback rules. Empty shops simply leave the icon blank.
- **MailboxPage Unchanged** — ITEM mails continue to render the actual item icon and MONEY mails the gold-bar icon; no functional change there.
- **Pitfalls Avoided** — All new `.ui` content is pure ASCII (verified `LC_ALL=C grep -c '[^ -~]' ShopEdit.ui ShopBrowse.ui` returns 0). No `ShrinkTextToFit`, `MinShrinkTextToFitFontSize`, `LetterSpacing`, `WrapText`, or `VerticalAlignment: Top`. No root-level `@Macro` definitions for tiles - each of the 24 tiles is inlined explicitly. Selector overlay uses `Group` + `Background` + `.Visible` toggling (no `.Style` mutation issues).

### Verification

- `./gradlew :mods:shops:build` - BUILD SUCCESSFUL
- `LC_ALL=C grep -c '[^ -~]' ShopEdit.ui` - 0 (pure ASCII)
- `LC_ALL=C grep -c '[^ -~]' ShopBrowse.ui` - 0 (pure ASCII)

## [1.0.0-alpha3] - 2026-04-13

### Mailbox Feature

- **New `Mailbox_Block` Item** — Craftable at Carpentrybench (4 Oak Planks + 1 Iron Bar). Placeable, rotatable (NESW). Reuses the Shop_Block model/texture/animation for now. Registered interaction `mailbox_block_use` via new `MailboxBlockBlockInteraction` codec.
- **Mail-Based Purchase Flow** — Sales no longer deliver items directly to the buyer's inventory or top up the shop owner's `shopBalance`. Every purchase now creates two mails:
  - **ITEM mail** for the buyer with the purchased itemId + quantity.
  - **MONEY mail** for the player-shop owner with the net sale revenue (`totalPrice - tax`). Admin shops skip the money mail (no owner).
  - `ShopService.purchaseItem()` wraps both mail inserts in try/catch and rolls back the buyer's wallet + stock if either insert fails.
  - The buyer gets a `shop.buy.sent_to_mailbox` chat confirmation immediately.
- **`shopBalance` = Buyback Pool Only** — The field no longer represents earnings. It is now exclusively the pool used to pay customers who sell back to the shop. Owners top it up via `/ksshop deposit`. Customer buybacks drain it. Displayed in the editor's Revenue panel under the new `BUYBACK POOL` label (was `SHOP BALANCE`).
- **`MailboxPage` UI (500×540)** — 6 mail rows per page with per-row icon (item icon for ITEM, gold bar for MONEY), detail line, relative time, and Claim button. Header shows item-mail count, money-mail count, and total pending value. Claim All button in the header. Prev/Next pagination. Opens on F-press on any placed Mailbox_Block. Claim idempotency: re-fetches mail before dispensing to handle races, skips if already claimed.
- **Claim Flow** — ITEM claim calls the Priority 1 `giveItem()` (now public) which handles inventory overflow by dropping at feet. MONEY claim calls `economyBridge.deposit()` and marks the mail claimed. Claim All iterates the unclaimed list and reports a summary `Claimed N mail(s): X items, Y gold.`
- **Legacy Balance Migration** — On plugin startup, `ShopService.migrateLegacyShopBalances()` iterates player shops with `shopBalance > 0` (which under the old model represented uncollected earnings) and converts each to a single MONEY mail with buyer name `[Legacy Balance]`, then sets `shopBalance = 0`. One-shot, guarded by a `.legacy_balances_migrated` marker file in the plugin data folder.
- **`/ksshop collect` Redirected** — The command no longer drains shopBalance. It now prints `shop.collect.use_mailbox` with the unclaimed-mail count, pointing the player at their Mailbox block. `ShopService.collectEarnings()` is kept as `@Deprecated` for future admin tooling but unused by player-facing code.
- **`/ksshop stats` Updated** — The "Pending earnings" line was fed from `shopBalance` which no longer tracks earnings. Replaced with a "Pending mails" line that shows `mailboxService.countUnclaimedForPlayer()`.
- **New DB Table `shop_mailbox`** — Columns: `id`, `owner_uuid`, `mail_type` (ITEM/MONEY), `item_id`, `quantity`, `amount`, `from_shop_id`, `from_shop_name`, `from_player_name`, `created_at`, `claimed`. Index on `(owner_uuid, claimed)` for fast unclaimed lookups. SQLite + MySQL schemas.
- **Localization v8** — Bumped from v6 to v8 across both phases. Added 20+ new keys: `shop.mailbox.*` (12 UI keys), `items.Mailbox_Block.*` (2 item labels), `shop.buy.sent_to_mailbox`, `shop.collect.use_mailbox`, `shop.revenue.buyback_pool`, `shop.stats.pending_mails`, `shop.migration.legacy_balances`. `shop.help.gs.step5` and `shop.help.collect` rewritten to point at the Mailbox block.

## [1.0.0-alpha2] - 2026-04-13

### Priority 1 Bug Fixes (Code Review Phase 1)

#### Critical
- **Item Duplication Exploit (BUG #2)** — Players could pull items out of their shop through the editor and close without saving to duplicate them. The dupe has been closed by a new reverse-diff pass in `ShopEditPage.onDismiss()`: on discard, any item the player extracted from the shop during the session is now removed from their hotbar/storage before the page closes, restoring the invariant that the DB still owns those items.
- **Silent Item Loss on Purchase (BUG #1)** — `ShopService.giveItem()` ignored `addItemStack`'s remainder, so buyers with full storage were charged but their items vanished. Now routes through `PlayerInventoryAccess.getCombinedStorageFirst()`, reads the transaction result, and drops any remainder at the player's feet via `SimpleItemContainer.addOrDropItemStack` fallback chain. No more invisible losses after checkout.
- **Missing i18n Keys (BUG #4)** — 30+ keys referenced by `ShopCommand` and `ShopEditPage` were not defined in `en-US.json`, so affected flows fell back to raw key strings. Added full coverage for `shop.create.*`, `shop.edit.*`, `shop.rename.*`, `shop.myshops.*`, `shop.status.*`, `shop.visit.*`, `shop.notifications.*`, `shop.error.*`, and `shop.help.*`. Bumped `_localization_version` to `2`.

#### Major
- **Item Loss in Editor Remove (BUG #3)** — `ShopEditPage.handleRemoveItem()` cleared the staged slot without returning its contents to the owner, silently destroying the stack. Items now get pushed back into the owner's storage container before the slot is cleared.
- **Self-Sell Exploit (BUG #6)** — Shop owners could sell their own items back to their shop, draining the shop balance into their pocket. `ShopService.sellItem()` now rejects transactions where the seller equals the owner (admin shops are exempt, mirroring the existing self-purchase prevention in `purchaseItem()`).
- **Tax UI Showing Fake Numbers (BUG #7)** — `ShopBrowsePage` was still computing and displaying a tax line even though the tax system is not active in the service layer, so the displayed total diverged from what players were actually charged. Removed all tax branches from `buildCards()`, `buildConfirmOverlay()`, and `getConfirmMaxQuantity()`. Players now see the real price.
- **Shop Position = Player Feet (BUG #9)** — `/ksshop create` saved the player's exact position as the shop location, which drifted from the actual shop block and broke `findShopAtBlock()` distance checks after walking away. `CreateCmd` now scans a 7×5×7 cube around the player for any `Shop_Block*` variant and centers the shop on the matched block's coordinates; creation is aborted with `shop.create.no_block_nearby` if none is found.

### Verification
- `./gradlew :mods:shops:build` — BUILD SUCCESSFUL
- Only pre-existing deprecation warnings remain (`getTransformComponent`, `getPlayerRef`) — no new warnings from Priority 1 changes

### Priority 2 Bug Fixes (Code Review Phase 2)

#### Usability
- **Help Command Getting-Started Block (BUG #8)** — `/ksshop help` previously dumped a raw command list with no context, leaving new players unsure where to start. `HelpCmd` now prints a five-step getting-started block at the top (craft a Shop Block, place it and `/ksshop create`, edit with drag & drop, deposit coins and `/ksshop open`, collect earnings), rendered in a distinct gold/light palette before the familiar command list. Seven new i18n keys (`shop.help.gs.header`, `shop.help.gs.step1..5`, `shop.help.gs.spacer`).
- **Owner-Perspective Editor Labels (BUG #10)** — The `ShopEditPage` is used by the shop owner, but the price fields were labeled as if the viewer were a customer ("Buy Price" / "Sell Price" without context). Relabeled to owner-native wording: "Sell to customers at" (shop's outgoing price), "Buy from customers at" (shop's buyback price), "Buyback quota", "Stock". `ShopEdit.ui` price/quota/stock labels widened from 80→140 px with `ShrinkTextToFit` to fit the longer copy; the corresponding five text fields slimmed from 80→70 px to keep the right panel aligned. Five label keys and four hint keys added under `shop.edit.label.*` / `shop.edit.hint.*` — populated in `buildSettingsPanel()` so translations respect the owner's language.
- **Unsaved-Changes Warning in Editor (BUG #11)** — Owners could close the shop editor with pending edits (new items, price/quota/mode changes, name/description/category edits) and silently lose them. `ShopEditPage` now tracks a `dirty` flag set by every mutation handler (`handleDrop`, `handleRemoveItem`, price/quota/stock/mode inputs, name/description/category commits) and cleared on successful save. On `onDismiss()` with `!saved && dirty`, the owner gets a `#ffaa00` chat warning via the new `shop.edit.unsaved_warning` key before the revert logic runs. No confirm dialog — chat warning only, to keep the close path non-blocking.

#### Robustness
- **Specific Error Messages on Shop Creation (BUG #5)** — `ShopService.createPlayerShop()` returned `null` for every kind of failure, forcing callers (`ShopCommand.CreateCmd`, `ShopCreatePage.handleCreate()`) to fall back on a generic "Shop creation failed" message. Introduced a new `CreateShopResult` value type with `success(ShopData)` / `error(String i18nKey)` factories; every failure branch now returns a specific error key (`shop.create.disabled`, `shop.create.economy_unavailable`, `shop.create.max_reached`, `shop.create.name_too_short`, `shop.create.name_too_long`, `shop.create.name_taken`, `shop.create.claim_required`, `shop.create.not_enough`, `shop.create.failed`). Both callers updated to surface the specific reason. Three previously-missing keys (`shop.create.disabled`, `shop.create.economy_unavailable`, `shop.create.claim_required`) added to `en-US.json`.
- **saveShop Items Persistence Audit (BUG #13)** — Investigated whether any `ShopService` path mutates item cache without a follow-up `saveShop()`. Finding: `ShopDatabase.saveShop()` already calls `saveShopItems()` internally, and every `ShopItem` mutation site in `purchaseItem`, `sellItem`, `ShopManager` sync, and `ShopEditPage` commit is followed by either `saveShop()` or a direct `saveShopItems()`. No code change required — this was a false alarm in the review.

#### Feature
- **Working Search / Delete / Rate Commands (BUG #12)** — Three subcommands were stubs that printed "coming soon...". They now do real work:
  - `SearchCmd` — scans all open shops for name-matches or item-id matches (case-insensitive `contains`) and prints up to 10 hits with owner label and item count. Empty query and no-results paths both have dedicated i18n.
  - `DeleteCmd` — resolves shop by UUID or case-insensitive name, verifies ownership, and implements a 60-second in-memory confirm window (`pendingDeletes`) keyed by the player UUID. First call stages a `DeleteConfirm` and prompts the player to re-run with `confirm`; second call despawns the NPC via `ShopNpcManager.despawnNpc()` and delegates to `ShopService.deletePlayerShop()` which handles refund, session cleanup, DB delete, and event firing. Showcase write guard respected.
  - `RateCmd` — validates stars 1–5, resolves the shop, rejects self-rating, constructs a `ShopRating`, persists via `ShopDatabase.saveRating()` (PK `rater_uuid, shop_id` so re-rating replaces), then reloads the full rating set and calls `ShopData.recalculateRating()` + `ShopDatabase.saveShop()` to refresh the cached average/count. Purchase-gate check is stubbed: the current `Ratings` config has no `requirePurchaseToRate` field, so the gate is a no-op until that field exists.
- **Localization v4** — Bumped `_localization_version` to `4` (another P2 agent bumped it to `3`). Added 13 new keys: `shop.help.gs.*` (7), `shop.search.*` (3), `shop.delete.confirm_prompt`/`confirmed`/`failed`, `shop.rate.invalid_stars`/`must_purchase`/`failed`. Refreshed `shop.delete.not_owner` and `shop.rate.success` wording to match the spec. `CURRENT_LOCALIZATION_VERSION` in `ShopI18n` bumped from `1` to `4` so installed user files get refreshed on next load.

### Priority 3 Polish (Code Review Phase 3)

#### Owner Dashboard
- **Real Revenue Today / This Week** — `ShopEditPage`'s revenue panel showed a literal `"--"` for the per-period revenue figures because no DB query existed. Added `ShopDatabase.getRevenueForShopSince(shopId, sinceTimestamp)` which sums `total_price` from `shop_transactions` with `type = 'BUY' AND timestamp >= ?`. `buildRevenuePanel()` now queries the last 24h window for Today and the last 7d window for This Week, formats via `economyBridge.format()`, and falls back to `"--"` only if the query throws.
- **Live Transaction History** — The history tab hid every `TxRow0-7` entry and always showed the empty state because there was no `getTransactionsForShop` helper. Added `ShopDatabase.loadTransactionsForShop(shopId, offset, limit)` + `countTransactionsForShop(shopId)`, and rewrote `buildHistoryPanel()` to populate icon / detail line ("Player bought 5x Iron Sword for 250G") / relative-time label / tint (green for BUY, amber for SELL) for up to 8 rows per page. `HistNavBar` becomes visible when `totalPages > 1`, `HistPageInfo` shows `page / total`, and `historyPage` is clamped into range so deleting transactions doesn't crash pagination. New helper `formatRelativeTime()` mirrors the one in `ShopCommand.HistoryCmd`/`ShopNotificationsPage`.

#### Economy
- **Per-Shop Collect Breakdown** — `/ksshop collect` previously printed just a total, so owners with many shops had no idea which one was paying out. Introduced a new `CollectResult` value type (`empty()` / `success(total, entries)` / `economyFailure()`) with a `ShopEntry` record exposing `shopId`, `shopName`, `amount`. `ShopService.collectEarnings()` now captures per-shop contributions BEFORE the deposit + reset step and returns the typed result. `CollectCmd` surfaces a three-state output: red economy-failure message, yellow empty-nothing message, or a green header followed by up to 10 grey per-shop lines (`- Shop Name: 340G`) with a truncation notice if there are more than 10 contributing shops. Four new i18n keys under `shop.collect.*`.

#### Power-User Commands
- **`/ksshop stats` — Personal Statistics** — New `StatsCmd` subcommand queries the owner's shops and prints a formatted summary: shops owned (`N / max`), total revenue summed across all shops, total tax paid, total sales (new `ShopDatabase.countSalesForOwner()` query), weighted average rating (`sum(avg * count) / sum(count)`), and pending earnings waiting to be collected. Skips the rating line if no ratings exist. Registered in the constructor and the help command.
- **`/ksshop transfer <shopId> <player> [confirm]` — Ownership Transfer** — New `TransferCmd` lets an owner hand their shop off to another online player. Mirrors the `DeleteCmd` confirmation pattern: a 60-second `pendingTransfers` map keyed by the sender's UUID. Validates shop ownership, resolves target via `world.getPlayerRefs()` with `equalsIgnoreCase`, rejects self-transfer and offline targets. On confirm, delegates to a new `ShopService.transferShop(shopId, newOwnerUuid, newOwnerName)` which updates owner fields, persists via `saveShop`, syncs `PlayerShopData.removeOwnedShop`/`addOwnedShop`, and releases any active editor lock held by the previous owner. `TransferCmd` then calls `npcManager.respawnNpc(shop)` so the nameplate/skin reflects the new owner, and notifies both sender and target in chat. `CoreBridge.showcaseWriteGuard` respected.

#### Localization v6
- Bumped `_localization_version` `4 → 5 → 6` across two P3 agents (collect breakdown = v5, stats/transfer = v6). `CURRENT_LOCALIZATION_VERSION` in `ShopI18n` matches. Added ~30 keys across `shop.collect.*`, `shop.stats.*`, `shop.transfer.*`, and two help-command lines.
