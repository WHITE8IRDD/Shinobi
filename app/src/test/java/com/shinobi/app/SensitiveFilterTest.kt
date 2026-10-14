package com.shinobi.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensitive filter must be exact: only YouTube's own age signals flag a card, and
 * ordinary content — including titles that merely mention "18+" — is never blurred.
 */
class SensitiveFilterTest {

    private fun video(title: String = "A perfectly ordinary video", extras: String = ""): JSONObject {
        val extraJson = if (extras.isBlank()) "" else ",$extras"
        return JSONObject(
            """
            {
              "videoId": "abcdefghijk",
              "title": {"runs": [{"text": "$title"}]},
              "ownerText": {"runs": [{"text": "Some channel"}]},
              "lengthText": {"simpleText": "10:00"}$extraJson
            }
            """.trimIndent(),
        )
    }

    @Test fun regularVideosAreNeverFlagged() {
        assertFalse(SensitiveFilter.flaggedInFeed(video()))
    }

    @Test fun aTitleThatMentions18PlusDoesNotFlag() {
        assertFalse(SensitiveFilter.flaggedInFeed(video(title = "18+ things to do")))
    }

    @Test fun ageRestrictionBadgeFlags() {
        val o = video(
            extras = """
            "badges": [
              {"metadataBadgeRenderer": {"style": "BADGE_STYLE_TYPE_SIMPLE", "label": "Age-restricted"}}
            ]""",
        )
        assertTrue(SensitiveFilter.flaggedInFeed(o))
    }

    @Test fun eighteenPlusBadgeTextFlags() {
        val o = video(
            extras = """
            "badges": [
              {"badgeViewModel": {"badgeText": "18+", "badgeStyle": "BADGE_DEFAULT"}}
            ]""",
        )
        assertTrue(SensitiveFilter.flaggedInFeed(o))
    }

    @Test fun ageRestrictedBadgeStyleFlags() {
        val o = video(
            extras = """
            "badges": [
              {"metadataBadgeRenderer": {"style": "BADGE_STYLE_TYPE_AGE_RESTRICTED", "label": "Something else"}}
            ]""",
        )
        assertTrue(SensitiveFilter.flaggedInFeed(o))
    }

    @Test fun familySafeFalseFlags() {
        assertTrue(SensitiveFilter.flaggedInFeed(video(extras = """"isFamilySafe": false""")))
    }

    @Test fun familySafeTrueDoesNotFlag() {
        assertFalse(SensitiveFilter.flaggedInFeed(video(extras = """"isFamilySafe": true""")))
    }

    @Test fun aViewsLabelIsNotABadge() {
        assertFalse(SensitiveFilter.flaggedInFeed(video(extras = """"viewCountText": {"simpleText": "18+ views"}""")))
    }
}
