"""Which regional-trending source still answers for a country?

YouTube retired the combined Trending page (21 July 2025): browseId "FEtrending" answers
HTTP 400. The per-category charts (YouTube Charts) only exist for the countries YouTube
Charts publishes, which does not include Morocco. This probe finds out, for the country
asked for, which public route still carries the country's real trending list, so the app
can use a route that actually works instead of the one that used to.

Every result is printed as a workflow annotation, so it is readable from the Checks API.
"""
import json
import sys
import urllib.error
import urllib.request

COUNTRY = sys.argv[1] if len(sys.argv) > 1 else "MA"
WEB_VERSION = "2.20260901.00.00"
MWEB_VERSION = "2.20260901.01.00"
API = "https://www.youtube.com/youtubei/v1/"


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


def videos(root):
    """(id, title, views, published) for every video renderer in the response."""
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


def describe(node):
    if not isinstance(node, dict):
        return None
    video_id = node.get("videoId") or node.get("contentId") or ""
    title = ""
    for key in ("title", "headline"):
        part = node.get(key)
        if isinstance(part, dict):
            title = part.get("simpleText") or "".join(
                run.get("text", "") for run in part.get("runs", []) or [])
            if title:
                break
    views = ""
    published = ""
    for key in ("viewCountText", "shortViewCountText", "publishedTimeText"):
        part = node.get(key)
        if isinstance(part, dict):
            text = part.get("simpleText") or "".join(
                run.get("text", "") for run in part.get("runs", []) or [])
            if key == "viewCountText" and text:
                views = text
            if key == "publishedTimeText" and text:
                published = text
    if not views:
        metadata = node.get("metadata")
        if isinstance(metadata, dict):
            blob = json.dumps(metadata)
            if "views" in blob:
                views = "meta"
    if not video_id or not title:
        return None
    return (video_id, title[:48], views, published)


def report(label, function):
    try:
        root = function()
    except urllib.error.HTTPError as error:
        print(f"::notice::TREND {label}: HTTP {error.code}")
        return None
    except Exception as error:  # noqa: BLE001 - a diagnostic reports every failure
        print(f"::notice::TREND {label}: {type(error).__name__}: {error}")
        return None
    found = videos(root)
    if not found:
        print(f"::notice::TREND {label}: 0 videos")
        return None
    head = "; ".join(f"{item[1]} [{item[2]}|{item[3]}]" for item in found[:3])
    print(f"::notice::TREND {label}: {len(found)} videos | {head}")
    return found


print(f"::notice::TREND probing country {COUNTRY}")

# 1. The retired general Trending feed, with every category parameter YouTube ever used.
for name, params in [
    ("FEtrending/now", ""),
    ("FEtrending/videos", "4gIOGgxtb3N0X3BvcHVsYXI%3D"),
    ("FEtrending/charts-all", "4gINGgt5dG1hX2NoYXJ0cw%3D%3D"),
    ("FEtrending/charts-music", "4gIuCggvbS8wNHJsZhIiU%3D%3D"),
    ("FEtrending/charts-movies", "4gJ2eT5H2r1pXzXz"),
]:
    query = {"browseId": "FEtrending"}
    if params:
        query["params"] = params
    report(name, lambda query=query: post("browse", query))
    report(name + "@mweb", lambda query=query: post("browse", query, "MWEB", "2", MWEB_VERSION))

# 2. The other places YouTube still keeps charts.
report("podcasts", lambda: post("browse", {"browseId": "FEpodcasts_destination", "params": "qgcCCAM%3D"}))
report("gaming", lambda: post("browse", {"browseId": "UCOpNcN46UbXVtpKMrmU4Abg", "params": "Egh0cmVuZGluZw%3D%3D"}))
report("explore", lambda: post("browse", {"browseId": "FEexplore"}))
report("home", lambda: post("browse", {"browseId": "FEwhat_to_watch"}))

# 3. Search filters: which one means "this week, most watched"? The response's own
#    publishedTimeText and viewCountText say which.
for name, params in [
    ("relevance", ""),
    ("upload-date", "CAI%3D"),
    ("view-count", "EgQIBRAB"),
    ("view-count-week", "CAMSAhAB"),
    ("view-count-month", "CAISAhAB"),
    ("week", "EgQIAhAB"),
    ("year", "EgQIAxAB"),
]:
    query = {"query": "news"}
    if params:
        query["params"] = params
    report("search/" + name, lambda query=query: post("search", query))

# 4. The old HTML trending page and the legacy "most popular" feed, for reference.
for label, url in [
    ("html/feed-trending", "https://www.youtube.com/feed/trending?gl=" + COUNTRY),
    ("feeds/most-popular", "https://www.youtube.com/feeds/videos.xml?chart=mostPopular&gl=" + COUNTRY),
]:
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=12) as response:
            body = response.read(400000).decode("utf-8", "replace")
        found = videos(json.loads(body)) if body.strip().startswith("{") else []
        print(f"::notice::TREND {label}: HTTP 200, {len(found)} videos, "
              f"length={len(body)}, trending_title={'Trending' in body}")
    except urllib.error.HTTPError as error:
        print(f"::notice::TREND {label}: HTTP {error.code}")
    except Exception as error:  # noqa: BLE001
        print(f"::notice::TREND {label}: {type(error).__name__}: {error}")

# 5. The route the app will actually use: a country's most-watched videos of the last week,
#    found through the search filters YouTube itself offers. Several topical queries are
#    merged, so the list holds news, current events, music and viral clips, not one topic.
for query in ["news", "viral", "music"]:
    for name, params in [("view+week", "CAISAhAB"), ("view+month", "CAMSAhAB"), ("plain", "")]:
        body = {"query": query}
        if params:
            body["params"] = params
        report(f"search/{query}/{name}", lambda body=body: post("search", body))

print("Diagnostic only. Shared runner IPs and device networks can receive different responses.")
