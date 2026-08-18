package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class ClanStore {
    static final int MAX_MEMBERS = 25;
    static final int DEFAULT_THEME_COLOR = 0xFF9900;
    static final long INVITE_TTL_MILLIS = 5 * 60 * 1000L;
    /**
     * How many other clans one clan may be allied to.
     *
     * <p>Capped because an alliance is a PvP truce: with no ceiling every clan allies
     * every other clan, the map quietly becomes peaceful, and the bounty board and
     * kill leaderboards stop meaning anything. Three is enough to take a side without
     * being able to take everyone's.
     */
    static final int MAX_ALLIES = 3;
    /** Long enough for the other clan's leader to be fetched, short enough to expire. */
    static final long ALLY_OFFER_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int FORMAT_VERSION = 2;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Z0-9]{2,6}");

    enum ClanRole {
        LEADER,
        STAFF,
        MEMBER
    }

    record ClanView(
            UUID id,
            String name,
            int themeColor,
            UUID leader,
            Map<UUID, String> members,
            Set<UUID> staff,
            int level,
            long treasury,
            int memberSlots,
            Map<UUID, Long> donations,
            Map<UUID, Long> joinedAt,
            /** Allied clan id to that clan's name, so callers can show it directly. */
            Map<UUID, String> allies
    ) {
        ClanRole roleOf(UUID playerId) {
            if (leader.equals(playerId)) {
                return ClanRole.LEADER;
            }
            return staff.contains(playerId) ? ClanRole.STAFF : ClanRole.MEMBER;
        }

        ClanLevel.Perks perks() {
            return ClanLevel.perksFor(level);
        }

        /** The level this clan would buy next, or empty when it holds the last one. */
        Optional<Integer> nextLevel() {
            return level >= ClanLevel.MAX_PUBLIC_LEVEL ? Optional.empty() : Optional.of(level + 1);
        }

        /** Money donated to the clan. It cannot be withdrawn. */
        long balance() {
            return treasury;
        }

        /** The next roster upgrade, or empty once the clan is at full size. */
        Optional<ClanLevel.MemberTier> nextMemberTier() {
            return ClanLevel.nextMemberTier(ClanLevel.tiersBoughtFor(memberSlots));
        }

        boolean alliedWith(UUID clanId) {
            return allies.containsKey(clanId);
        }

        /** Allied clan names, alphabetical, which is how every surface lists them. */
        List<String> allyNames() {
            return allies.values().stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        /** Lifetime contributors, largest first, which is how the donor board reads. */
        List<Map.Entry<UUID, Long>> rankedDonors() {
            List<Map.Entry<UUID, Long>> ranked = new ArrayList<>(donations.entrySet());
            ranked.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
            return ranked;
        }
    }

    private static final class SavedState {
        int version = FORMAT_VERSION;
        List<SavedClan> clans = new ArrayList<>();
        Map<String, SavedInvite> invites = new LinkedHashMap<>();
        /** Outstanding alliance offers, keyed {@code fromClanId>toClanId}. */
        Map<String, SavedAllyOffer> allyOffers = new LinkedHashMap<>();
    }

    private static final class SavedClan {
        String id;
        String name;
        String tag; // Read once when migrating data written by plugin 2.1.0.
        Integer themeColor;
        String leader;
        Map<String, String> members = new LinkedHashMap<>();
        Set<String> staff = new LinkedHashSet<>();
        /** Absent on clans saved before 2.28.0, which read back as an unupgraded clan. */
        Integer level;
        /** Dollars held. Absent before money vaults; leftover item maps are dropped. */
        Long treasury;
        /** Roster size bought so far. Absent reads as {@link ClanLevel#STARTING_MEMBER_SLOTS}. */
        Integer memberSlots;
        /** Player uuid to lifetime value donated. Never reduced by spending. */
        Map<String, Long> donations;
        /** Player uuid to epoch millis when they joined. Absent on older saves. */
        Map<String, Long> joinedAt;
        /**
         * Clan ids this clan has allied. Absent before 2.72.0, which reads as none.
         *
         * <p>Held on both clans rather than in one shared list, because the damage
         * check reads it from whichever clan it already has in hand. Load repairs any
         * one-sided entry by dropping it, so the two copies cannot disagree.
         */
        Set<String> allies;
    }

    private static final class SavedAllyOffer {
        String fromClanId;
        String toClanId;
        String offeredBy;
        long expiresAt;
    }

    /** What an ally command did: an offer was sent, or an alliance was formed. */
    record AllyResult(ClanView own, ClanView other, boolean formed) {
    }

    private static final class SavedInvite {
        String clanId;
        String invitedBy;
        long expiresAt;
    }

    static final class ClanException extends IllegalArgumentException {
        ClanException(String message) {
            super(message);
        }
    }

    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private SavedState state;
    private final Map<UUID, SavedClan> memberIndex = new LinkedHashMap<>();

    ClanStore(Path path) throws IOException {
        this.path = path;
        this.state = load(path);
        if (rebuildIndex()) {
            persist();
        }
    }

    synchronized ClanView create(UUID owner, String ownerName, String requestedName) throws IOException {
        requireNotInClan(owner, "You already have a clan!");
        String name = normalizeName(requestedName);
        requireUniqueName(name, null);
        SavedClan clan = new SavedClan();
        clan.id = UUID.randomUUID().toString();
        clan.name = name;
        clan.themeColor = DEFAULT_THEME_COLOR;
        clan.leader = owner.toString();
        clan.members.put(owner.toString(), cleanPlayerName(ownerName));
        clan.joinedAt = new LinkedHashMap<>();
        clan.joinedAt.put(owner.toString(), System.currentTimeMillis());
        state.clans.add(clan);
        memberIndex.put(owner, clan);
        persist();
        return view(clan);
    }

    /**
     * Deletes every clan, vault, donation ledger and outstanding invite.
     *
     * <p>For starting the season over after testing. Unlike {@link #disband(UUID)} this
     * asks nobody's permission and refunds nothing, so it is reachable only from the
     * administrative reset.
     *
     * @return how many clans were removed
     */
    synchronized int clearAll() throws IOException {
        int removed = state.clans.size();
        state.clans.clear();
        state.invites.clear();
        state.allyOffers.clear();
        memberIndex.clear();
        persist();
        return removed;
    }

    synchronized Optional<ClanView> clanOf(UUID playerId) {
        SavedClan clan = memberIndex.get(playerId);
        return clan == null ? Optional.empty() : Optional.of(view(clan));
    }

    synchronized Optional<ClanView> findClanById(UUID clanId) {
        if (clanId == null) {
            return Optional.empty();
        }
        return state.clans.stream()
                .filter(clan -> clan.id.equals(clanId.toString()))
                .findFirst()
                .map(this::view);
    }

    synchronized Optional<ClanView> findClan(String name) {
        String lookup = normalizeLookup(name);
        return state.clans.stream()
                .filter(clan -> clan.name.toLowerCase(Locale.ROOT).equals(lookup))
                .findFirst()
                .map(this::view);
    }

    synchronized List<ClanView> list() {
        return state.clans.stream()
                .map(this::view)
                .sorted(Comparator.comparing(ClanView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized List<ClanView> listByWealth() {
        return state.clans.stream()
                .map(this::view)
                .sorted(Comparator.comparingLong(ClanView::balance).reversed())
                .toList();
    }

    synchronized String invite(
            UUID actor,
            UUID target,
            String targetName,
            long now
    ) throws IOException {
        SavedClan clan = requireStaff(actor);
        if (actor.equals(target)) {
            throw new ClanException("You cannot invite yourself.");
        }
        requireNotInClan(target, "That player already has a clan.");
        if (clan.members.size() >= slotsOf(clan)) {
            throw new ClanException("Your clan is full at " + slotsOf(clan)
                    + " members. Buy another roster slot to invite more.");
        }
        pruneInvites(now);
        SavedInvite invite = new SavedInvite();
        invite.clanId = clan.id;
        invite.invitedBy = actor.toString();
        invite.expiresAt = now + INVITE_TTL_MILLIS;
        state.invites.put(target.toString(), invite);
        persist();
        return cleanPlayerName(targetName);
    }

    synchronized ClanView accept(UUID player, String playerName, long now) throws IOException {
        requireNotInClan(player, "You already have a clan!");
        pruneInvites(now);
        SavedInvite invite = state.invites.get(player.toString());
        if (invite == null) {
            throw new ClanException("You do not have an active clan invite.");
        }
        SavedClan clan = requireClan(UUID.fromString(invite.clanId));
        if (clan.members.size() >= slotsOf(clan)) {
            state.invites.remove(player.toString());
            persist();
            throw new ClanException("That clan is full.");
        }
        clan.members.put(player.toString(), cleanPlayerName(playerName));
        if (clan.joinedAt == null) {
            clan.joinedAt = new LinkedHashMap<>();
        }
        clan.joinedAt.put(player.toString(), now);
        memberIndex.put(player, clan);
        state.invites.remove(player.toString());
        persist();
        return view(clan);
    }

    synchronized void decline(UUID player) throws IOException {
        if (state.invites.remove(player.toString()) == null) {
            throw new ClanException("You do not have an active clan invite.");
        }
        persist();
    }

    /**
     * Offers an alliance, or accepts one already offered by that clan.
     *
     * <p>One command for both halves: whoever runs it second completes the alliance.
     * An alliance is mutual by construction, because a one-sided truce would let a
     * clan make itself unhittable by whoever it liked.
     */
    synchronized AllyResult ally(UUID actor, String targetClanName, long now) throws IOException {
        SavedClan own = requireStaff(actor);
        SavedClan other = requireClanByName(targetClanName);
        if (own.id.equals(other.id)) {
            throw new ClanException("You cannot ally with your own clan.");
        }
        if (alliesOf(own).contains(other.id)) {
            throw new ClanException("You are already allied with " + other.name + ".");
        }
        pruneAllyOffers(now);
        if (state.allyOffers.remove(offerKey(other.id, own.id)) != null) {
            requireAllyRoom(own);
            requireAllyRoom(other);
            alliesOf(own).add(other.id);
            alliesOf(other).add(own.id);
            // Neither clan needs the other's pending offer once they are allied.
            state.allyOffers.remove(offerKey(own.id, other.id));
            persist();
            return new AllyResult(view(own), view(other), true);
        }
        requireAllyRoom(own);
        if (state.allyOffers.containsKey(offerKey(own.id, other.id))) {
            throw new ClanException("You have already offered " + other.name
                    + " an alliance. They have to accept it.");
        }
        SavedAllyOffer offer = new SavedAllyOffer();
        offer.fromClanId = own.id;
        offer.toClanId = other.id;
        offer.offeredBy = actor.toString();
        offer.expiresAt = now + ALLY_OFFER_TTL_MILLIS;
        state.allyOffers.put(offerKey(own.id, other.id), offer);
        persist();
        return new AllyResult(view(own), view(other), false);
    }

    /**
     * Ends an alliance.
     *
     * <p>Deliberately one-sided: forming a truce needs both clans to agree, leaving
     * one needs only the clan that wants out. Requiring consent to break it would let
     * a clan hold another in a truce it no longer wants.
     */
    synchronized AllyResult unally(UUID actor, String targetClanName) throws IOException {
        SavedClan own = requireStaff(actor);
        SavedClan other = requireClanByName(targetClanName);
        if (!alliesOf(own).remove(other.id)) {
            throw new ClanException("You are not allied with " + other.name + ".");
        }
        alliesOf(other).remove(own.id);
        persist();
        return new AllyResult(view(own), view(other), false);
    }

    /**
     * Whether these two players are barred from damaging each other: same clan, or
     * clans that have allied.
     *
     * <p>Answered straight off the member index. The damage event fires for every
     * arrow, swing and splash, and building a {@link ClanView} rebuilds both rosters —
     * parsing a UUID per member — which is far too much work to do per hit.
     */
    synchronized boolean pvpBlocked(UUID attacker, UUID victim) {
        SavedClan attackerClan = memberIndex.get(attacker);
        if (attackerClan == null) {
            return false;
        }
        SavedClan victimClan = memberIndex.get(victim);
        if (victimClan == null) {
            return false;
        }
        if (attackerClan.id.equals(victimClan.id)) {
            return true;
        }
        return attackerClan.allies != null && attackerClan.allies.contains(victimClan.id);
    }

    synchronized ClanView rename(UUID actor, String requestedName) throws IOException {
        SavedClan clan = requireLeader(actor);
        String name = normalizeName(requestedName);
        if (clan.name.equals(name)) {
            throw new ClanException("Your clan already uses that name.");
        }
        requireUniqueName(name, UUID.fromString(clan.id));
        clan.name = name;
        persist();
        return view(clan);
    }

    synchronized ClanView setThemeColor(UUID actor, String requestedColor) throws IOException {
        SavedClan clan = requireLeader(actor);
        int themeColor = parseThemeColor(requestedColor);
        if (clan.themeColor == themeColor) {
            throw new ClanException("Your clan already uses that theme color.");
        }
        clan.themeColor = themeColor;
        persist();
        return view(clan);
    }

    /**
     * Banks dollars and credits the donor.
     *
     * <p>Donations are one-way: there is no withdraw, and disbanding destroys the
     * treasury. The ledger it credits is lifetime value and is never reduced by
     * spending, so someone who funded an upgrade keeps the credit for it.
     */
    synchronized long donate(UUID actor, long amount) throws IOException {
        SavedClan clan = requireClanForMember(actor);
        if (amount <= 0L) {
            throw new ClanException("Donate a whole dollar amount.");
        }
        clan.treasury = treasuryOf(clan) + amount;
        if (clan.donations == null) {
            clan.donations = new LinkedHashMap<>();
        }
        clan.donations.merge(actor.toString(), amount, Long::sum);
        persist();
        return amount;
    }

    /** Buys the next rung of the roster ladder out of the treasury. */
    synchronized ClanView upgradeMembers(UUID actor) throws IOException {
        SavedClan clan = requireStaff(actor);
        int slots = slotsOf(clan);
        ClanLevel.MemberTier tier = ClanLevel.nextMemberTier(ClanLevel.tiersBoughtFor(slots))
                .orElseThrow(() -> new ClanException("Your clan already holds every roster slot."));
        long missing = ClanLevel.shortfall(treasuryOf(clan), tier.cost());
        if (missing > 0L) {
            throw new ClanException("The clan needs " + EconomyFormat.dollars(missing) + " more.");
        }
        clan.treasury = treasuryOf(clan) - tier.cost().dollars();
        clan.memberSlots = tier.slots();
        persist();
        return view(clan);
    }

    /**
     * Spends the treasury on the next level. Checked and debited in one persisted
     * step so a clan cannot be charged for an upgrade it does not receive.
     */
    synchronized ClanView upgrade(UUID actor) throws IOException {
        SavedClan clan = requireStaff(actor);
        int current = levelOf(clan);
        if (current >= ClanLevel.MAX_PUBLIC_LEVEL) {
            throw new ClanException("Your clan is already at the highest level.");
        }
        int next = current + 1;
        ClanLevel.Cost cost = ClanLevel.costOf(next)
                .orElseThrow(() -> new ClanException("Your clan is already at the highest level."));
        long missing = ClanLevel.shortfall(treasuryOf(clan), cost);
        if (missing > 0L) {
            throw new ClanException("The clan needs " + EconomyFormat.dollars(missing) + " more.");
        }
        clan.treasury = treasuryOf(clan) - cost.dollars();
        clan.level = next;
        persist();
        return view(clan);
    }

    /**
     * Takes money out of the treasury for a clan bounty. Leader only. Donations
     * stay on the ledger; this is a spend, not a withdraw back to a player.
     */
    synchronized ClanView spendTreasury(UUID actor, long amount) throws IOException {
        if (amount <= 0L) {
            throw new ClanException("Spend a whole dollar amount.");
        }
        SavedClan clan = requireLeader(actor);
        long held = treasuryOf(clan);
        if (held < amount) {
            throw new ClanException(
                    "The clan needs " + EconomyFormat.dollars(amount - held) + " more."
            );
        }
        clan.treasury = held - amount;
        persist();
        return view(clan);
    }

    private static int slotsOf(SavedClan clan) {
        return clan.memberSlots == null ? ClanLevel.STARTING_MEMBER_SLOTS : clan.memberSlots;
    }

    private static Map<UUID, Long> donationsOf(SavedClan clan) {
        if (clan.donations == null || clan.donations.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, Long> donations = new LinkedHashMap<>();
        clan.donations.forEach((id, value) -> donations.put(UUID.fromString(id), value));
        return Map.copyOf(donations);
    }

    private static long treasuryOf(SavedClan clan) {
        return clan.treasury == null ? 0L : clan.treasury;
    }

    private static int levelOf(SavedClan clan) {
        return clan.level == null ? 0 : clan.level;
    }

    synchronized ClanView setStaff(UUID actor, UUID target, boolean promoted) throws IOException {
        SavedClan clan = requireLeader(actor);
        requireMember(clan, target);
        if (actor.equals(target)) {
            throw new ClanException("The clan leader already has every management permission.");
        }
        boolean changed = promoted
                ? clan.staff.add(target.toString())
                : clan.staff.remove(target.toString());
        if (!changed) {
            throw new ClanException(promoted
                    ? "That player is already clan staff."
                    : "That player is not clan staff.");
        }
        persist();
        return view(clan);
    }

    synchronized ClanView transfer(UUID actor, UUID target) throws IOException {
        SavedClan clan = requireLeader(actor);
        requireMember(clan, target);
        if (actor.equals(target)) {
            throw new ClanException("You are already the clan leader.");
        }
        clan.staff.remove(target.toString());
        clan.staff.add(actor.toString());
        clan.leader = target.toString();
        persist();
        return view(clan);
    }

    synchronized String kick(UUID actor, UUID target) throws IOException {
        SavedClan clan = requireStaff(actor);
        requireMember(clan, target);
        if (actor.equals(target)) {
            if (clan.leader.equals(actor.toString())) {
                throw new ClanException("Transfer leadership or disband the clan before leaving.");
            }
            throw new ClanException("Use /clans leave to leave your clan.");
        }
        if (clan.leader.equals(target.toString())) {
            throw new ClanException("The clan leader cannot be kicked.");
        }
        if (!clan.leader.equals(actor.toString()) && clan.staff.contains(target.toString())) {
            throw new ClanException("Only the clan leader can remove clan staff.");
        }
        String name = clan.members.remove(target.toString());
        clan.staff.remove(target.toString());
        memberIndex.remove(target);
        persist();
        return name;
    }

    synchronized String leave(UUID player) throws IOException {
        SavedClan clan = requireClanForMember(player);
        if (clan.leader.equals(player.toString())) {
            throw new ClanException("Transfer leadership or disband the clan before leaving.");
        }
        String name = clan.name;
        clan.members.remove(player.toString());
        clan.staff.remove(player.toString());
        memberIndex.remove(player);
        persist();
        return name;
    }

    /**
     * Disbands the clan. The vault goes with it — donations are one-way, and handing
     * it to the leader would be a withdraw route in all but name. Callers must warn
     * before reaching here; the returned view still carries the balance destroyed so
     * the message can name the figure.
     */
    synchronized ClanView disband(UUID actor) throws IOException {
        SavedClan clan = requireLeader(actor);
        ClanView disbanded = view(clan);
        UUID clanId = UUID.fromString(clan.id);
        state.clans.remove(clan);
        for (String memberId : clan.members.keySet()) {
            memberIndex.remove(UUID.fromString(memberId));
        }
        state.invites.entrySet().removeIf(entry -> entry.getValue().clanId.equals(clanId.toString()));
        // Both halves of every alliance point at this clan; leaving the other half
        // behind would keep its members unhittable by a clan that no longer exists.
        for (SavedClan remaining : state.clans) {
            if (remaining.allies != null) {
                remaining.allies.remove(clan.id);
            }
        }
        state.allyOffers.values().removeIf(offer ->
                offer.fromClanId.equals(clan.id) || offer.toClanId.equals(clan.id));
        persist();
        return disbanded;
    }

    synchronized Optional<UUID> findMember(UUID clanId, String playerName) {
        SavedClan clan;
        try {
            clan = requireClan(clanId);
        } catch (ClanException exception) {
            return Optional.empty();
        }
        String lookup = normalizeLookup(playerName);
        return clan.members.entrySet().stream()
                .filter(entry -> entry.getValue().toLowerCase(Locale.ROOT).equals(lookup))
                .map(entry -> UUID.fromString(entry.getKey()))
                .findFirst();
    }

    synchronized void touchPlayerName(UUID playerId, String playerName) throws IOException {
        SavedClan clan = memberIndex.get(playerId);
        if (clan == null) {
            return;
        }
        String cleanName = cleanPlayerName(playerName);
        if (!cleanName.equals(clan.members.get(playerId.toString()))) {
            clan.members.put(playerId.toString(), cleanName);
            persist();
        }
    }

    private SavedState load(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) == 0) {
            return new SavedState();
        }
        try {
            SavedState loaded = gson.fromJson(Files.readString(source, StandardCharsets.UTF_8), SavedState.class);
            if (loaded == null || loaded.version < 1 || loaded.version > FORMAT_VERSION) {
                throw new IOException("Unsupported clans.json format version");
            }
            if (loaded.clans == null) {
                loaded.clans = new ArrayList<>();
            }
            if (loaded.invites == null) {
                loaded.invites = new LinkedHashMap<>();
            }
            if (loaded.allyOffers == null) {
                loaded.allyOffers = new LinkedHashMap<>();
            }
            return loaded;
        } catch (JsonParseException exception) {
            throw new IOException("clans.json is not valid JSON", exception);
        }
    }

    private boolean rebuildIndex() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Set<UUID> clanIds = new LinkedHashSet<>();
        boolean migrated = state.version < FORMAT_VERSION;
        if (migrated) {
            state.version = FORMAT_VERSION;
        }
        try {
            for (SavedClan clan : state.clans) {
                UUID clanId = UUID.fromString(clan.id);
                UUID leader = UUID.fromString(clan.leader);
                if (!clanIds.add(clanId)) {
                    throw new IOException("Clan IDs and names must be unique");
                }
                String identity = clan.tag == null || clan.tag.isBlank()
                        ? clan.name
                        : clan.tag;
                String migratedName = identity == null ? "" : identity.trim().toUpperCase(Locale.ROOT);
                if (!VALID_NAME.matcher(migratedName).matches()) {
                    migratedName = availableLegacyName(clan.name, names);
                }
                if (!names.add(normalizeLookup(migratedName))) {
                    migratedName = availableLegacyName(migratedName, names);
                    names.add(normalizeLookup(migratedName));
                }
                migrated |= !migratedName.equals(clan.name) || clan.tag != null;
                clan.name = migratedName;
                clan.tag = null;
                if (clan.themeColor == null) {
                    clan.themeColor = DEFAULT_THEME_COLOR;
                    migrated = true;
                } else if (clan.themeColor < 0 || clan.themeColor > 0xFFFFFF) {
                    throw new IOException("Clan theme colors must be valid RGB values");
                }
                if (clan.members == null || !clan.members.containsKey(leader.toString())) {
                    throw new IOException("A clan leader must be present in its member list");
                }
                if (clan.members.isEmpty() || clan.members.size() > MAX_MEMBERS) {
                    throw new IOException("Clan member counts must be between 1 and " + MAX_MEMBERS);
                }
                if (clan.memberSlots == null) {
                    // Clans that predate the roster ladder were built under a flat
                    // 25-player cap. Grant them the smallest tier that still holds
                    // everyone, rather than dropping them to 3 and stranding members.
                    clan.memberSlots = ClanLevel.smallestSlotCountHolding(clan.members.size());
                    migrated = true;
                }
                if (clan.members.size() > clan.memberSlots) {
                    throw new IOException("A clan cannot hold more members than its roster allows");
                }
                if (clan.level != null && clan.level == 6) {
                    clan.level = ClanLevel.MAX_PUBLIC_LEVEL;
                    migrated = true;
                }
                if (clan.level != null && !ClanLevel.isValid(clan.level)) {
                    throw new IOException("Clan levels must be between 0 and " + ClanLevel.MAX_PUBLIC_LEVEL);
                }
                if (clan.treasury == null) {
                    clan.treasury = 0L;
                    migrated = true;
                }
                if (clan.treasury < 0L) {
                    throw new IOException("Clan treasury cannot be negative");
                }
                if (clan.memberSlots != null && !ClanLevel.isValidSlotCount(clan.memberSlots)) {
                    throw new IOException("Clan roster sizes must come from the member ladder");
                }
                if (clan.donations != null) {
                    for (Map.Entry<String, Long> entry : clan.donations.entrySet()) {
                        UUID.fromString(entry.getKey());
                        if (entry.getValue() == null || entry.getValue() < 0) {
                            throw new IOException("Clan donation totals cannot be negative");
                        }
                    }
                }
                if (clan.staff == null) {
                    clan.staff = new LinkedHashSet<>();
                }
                clan.staff.remove(leader.toString());
                clan.staff.removeIf(member -> !clan.members.containsKey(member));
                for (Map.Entry<String, String> member : clan.members.entrySet()) {
                    UUID memberId = UUID.fromString(member.getKey());
                    if (member.getValue() == null || member.getValue().isBlank()) {
                        throw new IOException("Clan members must have a saved player name");
                    }
                    if (memberIndex.putIfAbsent(memberId, clan) != null) {
                        throw new IOException("A player cannot belong to multiple clans");
                    }
                }
            }
            Set<String> knownClanIds = new LinkedHashSet<>();
            state.clans.forEach(clan -> knownClanIds.add(clan.id));
            migrated |= repairAlliances(knownClanIds);
            for (Map.Entry<String, SavedInvite> entry : state.invites.entrySet()) {
                UUID.fromString(entry.getKey());
                SavedInvite invite = entry.getValue();
                if (invite == null
                        || !knownClanIds.contains(invite.clanId)
                        || invite.invitedBy == null
                        || invite.expiresAt <= 0) {
                    throw new IOException("clans.json contains an invalid clan invite");
                }
                UUID.fromString(invite.invitedBy);
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("clans.json contains invalid clan data", exception);
        }
        return migrated;
    }

    private void persist() throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(state), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private SavedClan requireClanForMember(UUID member) {
        SavedClan clan = memberIndex.get(member);
        if (clan == null) {
            throw new ClanException("You are not in a clan.");
        }
        return clan;
    }

    private SavedClan requireClan(UUID clanId) {
        return state.clans.stream()
                .filter(candidate -> candidate.id.equals(clanId.toString()))
                .findFirst()
                .orElseThrow(() -> new ClanException("That clan no longer exists."));
    }

    private SavedClan requireStaff(UUID actor) {
        SavedClan clan = requireClanForMember(actor);
        if (!clan.leader.equals(actor.toString()) && !clan.staff.contains(actor.toString())) {
            throw new ClanException("Only clan staff can do that.");
        }
        return clan;
    }

    private SavedClan requireLeader(UUID actor) {
        SavedClan clan = requireClanForMember(actor);
        if (!clan.leader.equals(actor.toString())) {
            throw new ClanException("Only the clan leader can do that.");
        }
        return clan;
    }

    private void requireNotInClan(UUID member, String message) {
        if (memberIndex.containsKey(member)) {
            throw new ClanException(message);
        }
    }

    private void requireMember(SavedClan clan, UUID member) {
        if (!clan.members.containsKey(member.toString())) {
            throw new ClanException("That player is not in your clan.");
        }
    }

    private void requireUniqueName(String name, UUID exceptId) {
        String lookup = normalizeLookup(name);
        boolean taken = state.clans.stream().anyMatch(clan ->
                (exceptId == null || !clan.id.equals(exceptId.toString()))
                        && clan.name.toLowerCase(Locale.ROOT).equals(lookup)
        );
        if (taken) {
            throw new ClanException("A clan with that name already exists.");
        }
    }

    private static String availableLegacyName(String name, Set<String> takenNames) {
        String base = (name == null ? "" : name)
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        if (base.length() < 2) {
            base = "CLAN";
        }
        base = base.substring(0, Math.min(6, base.length()));
        for (int suffix = 0; suffix < 100; suffix++) {
            String candidate = suffix == 0
                    ? base
                    : base.substring(0, Math.min(4, base.length())) + suffix;
            if (!takenNames.contains(normalizeLookup(candidate))) {
                return candidate;
            }
        }
        throw new ClanException("Could not migrate a unique clan name.");
    }

    /**
     * Makes the two halves of every alliance agree, and drops anything pointing at a
     * clan that is gone.
     *
     * <p>An alliance is stored on both clans, so a crash between the two writes could
     * leave one side allied and the other not — and the damage check reads whichever
     * clan it has in hand, so the truce would apply in one direction only. A
     * half-written alliance is dropped rather than completed: the clans can ally
     * again in one command, and silently granting a truce nobody confirmed is worse
     * than asking them to repeat themselves.
     *
     * @return whether anything had to be changed, so the caller writes it back
     */
    private boolean repairAlliances(Set<String> knownClanIds) {
        boolean changed = false;
        // Trim first, so a lowered MAX_ALLIES drops entries before the mutual pass
        // reads them and can strand the other half.
        for (SavedClan clan : state.clans) {
            if (clan.allies != null && clan.allies.size() > MAX_ALLIES) {
                clan.allies = new LinkedHashSet<>(
                        new ArrayList<>(clan.allies).subList(0, MAX_ALLIES));
                changed = true;
            }
        }
        // One immutable snapshot for the whole pass. Reading the live sets while
        // removing from them makes the result depend on clan order: A could be
        // dropped from B by an earlier iteration and then B kept on A by a later one,
        // rebuilding the asymmetry this exists to remove.
        Map<String, Set<String>> named = new LinkedHashMap<>();
        for (SavedClan clan : state.clans) {
            named.put(clan.id, clan.allies == null ? Set.of() : Set.copyOf(clan.allies));
        }
        for (SavedClan clan : state.clans) {
            if (clan.allies == null) {
                continue;
            }
            changed |= clan.allies.removeIf(allyId ->
                    allyId == null
                            || allyId.equals(clan.id)
                            || !knownClanIds.contains(allyId)
                            || !named.getOrDefault(allyId, Set.of()).contains(clan.id));
        }
        changed |= state.allyOffers.entrySet().removeIf(entry -> {
            SavedAllyOffer offer = entry.getValue();
            return offer == null
                    || offer.fromClanId == null
                    || offer.toClanId == null
                    || offer.expiresAt <= 0
                    || !knownClanIds.contains(offer.fromClanId)
                    || !knownClanIds.contains(offer.toClanId)
                    || !entry.getKey().equals(offerKey(offer.fromClanId, offer.toClanId));
        });
        return changed;
    }

    private static String offerKey(String fromClanId, String toClanId) {
        return fromClanId + ">" + toClanId;
    }

    /** The live ally set, created on first use so older saves upgrade in place. */
    private static Set<String> alliesOf(SavedClan clan) {
        if (clan.allies == null) {
            clan.allies = new LinkedHashSet<>();
        }
        return clan.allies;
    }

    private static void requireAllyRoom(SavedClan clan) {
        if (alliesOf(clan).size() >= MAX_ALLIES) {
            throw new ClanException(clan.name + " already has " + MAX_ALLIES
                    + " allies, which is the limit.");
        }
    }

    private SavedClan requireClanByName(String name) {
        if (name == null || name.isBlank()) {
            throw new ClanException("Name the clan.");
        }
        String lookup = normalizeLookup(name);
        return state.clans.stream()
                .filter(clan -> clan.name.toLowerCase(Locale.ROOT).equals(lookup))
                .findFirst()
                .orElseThrow(() -> new ClanException("No clan is called " + name + "."));
    }

    private void pruneAllyOffers(long now) {
        state.allyOffers.values().removeIf(offer -> offer.expiresAt <= now);
    }

    private void pruneInvites(long now) {
        state.invites.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private ClanView view(SavedClan clan) {
        LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        clan.members.forEach((id, name) -> members.put(UUID.fromString(id), name));
        LinkedHashSet<UUID> staff = new LinkedHashSet<>();
        clan.staff.forEach(id -> staff.add(UUID.fromString(id)));
        return new ClanView(
                UUID.fromString(clan.id),
                clan.name,
                clan.themeColor,
                UUID.fromString(clan.leader),
                Map.copyOf(members),
                Set.copyOf(staff),
                levelOf(clan),
                treasuryOf(clan),
                slotsOf(clan),
                donationsOf(clan),
                joinedAtOf(clan),
                allyNamesOf(clan)
        );
    }

    /** Allied clan ids mapped to their names. Capped at {@link #MAX_ALLIES}, so small. */
    private Map<UUID, String> allyNamesOf(SavedClan clan) {
        if (clan.allies == null || clan.allies.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, String> named = new LinkedHashMap<>();
        for (String allyId : clan.allies) {
            for (SavedClan candidate : state.clans) {
                if (candidate.id.equals(allyId)) {
                    named.put(UUID.fromString(allyId), candidate.name);
                    break;
                }
            }
        }
        return Map.copyOf(named);
    }

    private static Map<UUID, Long> joinedAtOf(SavedClan clan) {
        if (clan.joinedAt == null || clan.joinedAt.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, Long> joined = new LinkedHashMap<>();
        clan.joinedAt.forEach((id, at) -> joined.put(UUID.fromString(id), at));
        return Map.copyOf(joined);
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!VALID_NAME.matcher(normalized).matches()) {
            throw new ClanException("Clan names must contain 2-6 letters or numbers.");
        }
        return normalized;
    }

    private static String normalizeLookup(String value) {
        return (value == null ? "" : value.trim().replaceAll("\\s+", " ")).toLowerCase(Locale.ROOT);
    }

    private static int parseThemeColor(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("[0-9A-Fa-f]{6}")) {
            throw new ClanException("Use a named color or a six-digit hex color such as #55FFFF.");
        }
        return Integer.parseInt(normalized, 16);
    }

    private static String cleanPlayerName(String value) {
        String normalized = value == null ? "Player" : value.trim();
        return normalized.isEmpty() ? "Player" : normalized.substring(0, Math.min(normalized.length(), 32));
    }
}
