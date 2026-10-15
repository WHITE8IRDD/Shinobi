"""Which regional-trending source still answers for a country?

YouTube retired the combined Trending page (21 July 2025): browseId "FEtrending" answers
HTTP 400. The per-category charts (YouTube Charts) only exist for the countries YouTube
Charts publishes, which does not include Morocco. This probe finds out, for the country
asked for, which public route still carries the country's real trending list, so the app
can use a route that actually works instead of the one that used to.

The result is written to a report file that the workflow commits to the branch, so the
whole answer is readable without downloading a log.
"""
import json
import os
import sys
import urllib.error
import urllib.request

COUNTRY = sys.argv[1] if len(sys.argv) > 1 else "MA"
OUT = sys.argv[2] if len(sys.argv) > 2 else "trending-probe.md"
WEB_VERSION = "2.20260901.00.00"
MWEB_VERSION = "2.20260901.01.00"
API = "https://www.youtube.com/youtubei/v1/"

lines = []


def say(text):
    lines.append(text)
    print(text)


def post(endpoint, query, client="WEB", number="1", version=WEB_VERSION):
    payload = dict(query)
    payload["context"] = {
        "client": {
            "clientName": client,
            "clientVersion": version,
            "hl": "en",
            "gl": COUNTRY,
        }
    }
    request = urllib.request.Request(
        API + endpoint + "?prettyPrint=false",
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0",
            "X-Youtube-Client-Name": number,
            "X-Youtube-Client-Version": version,
            "Origin": "https://www.youtube.com",
        },
    )
    with urllib.request.urlopen(request, timeout=12) as response:
        return json.load(response)


def text_of(node):
    if not isinstance(node, dict):
        return ""
    part = node.get("title") or node.get("headline")
    if isinstance(part, dict):
        return part.get("simpleText") or "".join(
            run.get("text", "") for run in part.get("runs", []) or [])
    return ""


def describe(node):
    if not isinstance(node, dict):
        return None
    video_id = node.get("videoId") or node.get("contentId") or ""
    title = text_of(node)
    views = ""
    published = ""
    for key in ("viewCountText", "shortViewCountText", "publishedTimeText"):
        part = node.get(key)
        if isinstance(part, dict):
            value = part.get("simpleText") or "".join(
                run.get("text", "") for run in part.get("runs", []) or [])
            if key == "viewCountText" and value:
                views = value
            if key == "publishedTimeText" and value:
                published = value
    if not video_id or not title:
        return None
    return (video_id, title, views, published)


def videos(root):
    out = []

    def walk(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in ("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "lockupViewModel"):
                    item = describe(child)
                    if item:
                        out.append(item)
                else:
                    walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(root)
    return out


def report(label, function):
    try:
        root = function()
    except urllib.error.HTTPError as error:
        say(f"- **{label}**: HTTP {error.code}")
        return None
    except Exception as error:  # noqa: BLE001 - a diagnostic reports every failure
        say(f"- **{label}**: {type(error).__name__}: {error}")
        return None
    found = videos(root)
    if not found:
        say(f"- **{label}**: 0 videos")
        return None
    say(f"- **{label}**: {len(found)} videos")
    for item in found[:5]:
        say(f"    - `{item[0]}` {item[1][:60]} | views={item[2]!r} published={item[3]!r}")
    return found


say(f"# Regional trending probe — {COUNTRY}")
say("")
say("Which public route still carries this country's trending list.")

say("")
say("## Retired general Trending feed (browseId FEtrending)")
for name, params in [
    ("now", ""),
    ("videos", "4gIOGgxtb3N0X3BvcHVsYXI%3D"),
    ("charts-all", "4gINGgt5dG1hX2NoYXJ0cw%3D%3D"),
    ("charts-music", "4gIuCggvbS8wNHJsZhIiU%3D%3D"),
]:
    query = {"browseId": "FEtrending"}
    if params:
        query["params"] = params
    report(f"WEB {name}", lambda query=query: post("browse", query))
    report(f"MWEB {name}", lambda query=query: post("browse", query, "MWEB", "2", MWEB_VERSION))

say("")
say("## Other YouTube chart surfaces")
report("podcasts", lambda: post("browse", {"browseId": "FEpodcasts_destination", "params": "qgcCCAM%3D"}))
report("gaming", lambda: post("browse", {"browseId": "UCOpNcN46UbXVtpKMrmU4Abg", "params": "Egh0cmVuZGluZw%3D%3D"}))
report("explore", lambda: post("browse", {"browseId": "FEexplore"}))
report("home", lambda: post("browse", {"browseId": "FEwhat_to_watch"}))
report("charts-home", lambda: post("browse", {"browseId": "FEmusic_analytics_charts_home", "query": "perspective=CHART_DETAILS&chart_params_country_code=" + COUNTRY + "&chart_params_chart_type=TRENDING_VIDEOS"}))

say("")
say("## Search filters (which one means 'this week, most watched'?)")
for name, params in [
    ("plain", ""),
    ("sort-upload-date", "CAI%3D"),
    ("week", "EgQIBRAB"),
    ("month", "CAMSAhAB"),
    ("year", "CAISAhAB"),
    ("today", "EgIIAQ%3D%3D"),
]:
    body = {"query": "news"}
    if params:
        body["params"] = params
    report(f"search/{name}", lambda body=body: post("search", body))

say("")
say("## The route the app will use: most-watched of the last week, per topic")
for query in ["news", "viral", "music", "football"]:
    for name, params in [("view+week", "CAISAhAB"), ("view+month", "CAMSAhAB")]:
        body = {"query": query}
        if params:
            body["params"] = params
        report(f"search/{query}/{name}", lambda body=body: post("search", body))

say("")
say("## The old HTML routes, for reference")
for label, url in [
    ("html/feed-trending", "https://www.youtube.com/feed/trending?gl=" + COUNTRY),
    ("feeds/most-popular", "https://www.youtube.com/feeds/videos.xml?chart=mostPopular&gl=" + COUNTRY),
]:
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=12) as response:
            body = response.read(400000).decode("utf-8", "replace")
        say(f"- **{label}**: HTTP 200, {len(body)} bytes, "
            f"has-Trending-title={'Trending' in body}")
    except urllib.error.HTTPError as error:
        say(f"- **{label}**: HTTP {error.code}")
    except Exception as error:  # noqa: BLE001
        say(f"- **{label}**: {type(error).__name__}: {error}")

os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
with open(OUT, "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines) + "\n")
print("Diagnostic only. Shared runner IPs and device networks can receive different responses.")
