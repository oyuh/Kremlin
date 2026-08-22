package me.lawsonhart.kremlin.discord;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule that decides whether a role comes off.
 *
 * This is the shape of a real bug: "you should not have the VIP role" and "I could not work out
 * your rank just now" both produce an empty answer, and removing on both stripped every offline
 * player's rank role on the timer, and every team role whenever SimpleTeams was not answering.
 */
class RoleSyncTest {

    private static final String VERIFIED = "1";
    private static final String RANK_VIP = "10";
    private static final String RANK_STAFF = "11";
    private static final String TEAM_RAIDERS = "20";

    /** Mirrors the loop in RoleSync.apply: remove only what is both ours and decided. */
    private static Set<String> removed(final Set<String> held, final Set<String> wanted,
                                       final Set<String> removable) {
        final Set<String> out = new HashSet<>();
        for (final String role : held) {
            if (wanted.contains(role) || !removable.contains(role)) continue;
            out.add(role);
        }
        return out;
    }

    /**
     * The reported bug: linking took the player's team role. Team membership was undecidable, so
     * the team role must not be in the removable set at all.
     */
    @Test
    void anUndecidableTeamRoleIsLeftAlone() {
        Set<String> held = Set.of(VERIFIED, TEAM_RAIDERS);
        Set<String> wanted = Set.of(VERIFIED);
        Set<String> removable = Set.of(VERIFIED); // team not decidable this pass

        assertTrue(removed(held, wanted, removable).isEmpty(),
                "a team role we could not decide about must survive the pass");
    }

    /** The timer runs over everyone, and Vault cannot answer for an offline player. */
    @Test
    void anOfflinePlayerKeepsTheirRankRole() {
        Set<String> held = Set.of(VERIFIED, RANK_VIP);
        Set<String> wanted = Set.of(VERIFIED);
        Set<String> removable = Set.of(VERIFIED); // rank not decidable while offline

        assertTrue(removed(held, wanted, removable).isEmpty(),
                "the resync timer must not strip ranks off everybody who is logged out");
    }

    /** When we *can* decide, a role that is no longer earned still comes off. */
    @Test
    void aDecidedRoleThatIsNoLongerEarnedIsRemoved() {
        Set<String> held = Set.of(VERIFIED, RANK_VIP);
        Set<String> wanted = Set.of(VERIFIED, RANK_STAFF);
        Set<String> removable = Set.of(VERIFIED, RANK_VIP, RANK_STAFF);

        assertEquals(Set.of(RANK_VIP), removed(held, wanted, removable),
                "promotion still cleans up the old rank");
    }

    /** Leaving a team, when membership *is* readable, still removes the team role. */
    @Test
    void leavingATeamStillRemovesTheRole() {
        Set<String> held = Set.of(VERIFIED, TEAM_RAIDERS);
        Set<String> wanted = Set.of(VERIFIED);
        Set<String> removable = Set.of(VERIFIED, TEAM_RAIDERS);

        assertEquals(Set.of(TEAM_RAIDERS), removed(held, wanted, removable));
    }

    /** Unlinking is decidable for everything, so all of ours comes off. */
    @Test
    void unlinkingRemovesEverythingWeOwn() {
        Set<String> held = Set.of(VERIFIED, RANK_VIP, TEAM_RAIDERS, "someone-elses-role");
        Set<String> everything = Set.of(VERIFIED, RANK_VIP, RANK_STAFF, TEAM_RAIDERS);

        assertEquals(Set.of(VERIFIED, RANK_VIP, TEAM_RAIDERS), removed(held, Set.of(), everything));
    }

    /** A role nobody configured is never ours, decidable or not. */
    @Test
    void rolesWeDoNotOwnAreNeverTouched() {
        Set<String> held = Set.of("colour-role", "booster-role");
        assertTrue(removed(held, Set.of(), Set.of(VERIFIED, RANK_VIP)).isEmpty());
        for (String everything : List.of(VERIFIED, RANK_VIP, TEAM_RAIDERS)) {
            assertFalse(held.contains(everything));
        }
    }
}
