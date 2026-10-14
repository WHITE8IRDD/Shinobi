"""Public, cookie-free endpoint diagnostics. Do not treat runner-IP blocks as phone failures."""
import json
import urllib.request
import urllib.error


def count_videos(value):
    if isinstance(value, dict):
        return sum(1 if key in ('videoRenderer', 'compactVideoRenderer', 'gridVideoRenderer',
                                'lockupViewModel', 'shortsLockupViewModel', 'reelItemRenderer')
                   else count_videos(child) for key, child in value.items())
    if isinstance(value, list):
        return sum(count_videos(child) for child in value)
    return 0

for client, number, version in [('WEB', '1', '2.20260901.00.00'), ('MWEB', '2', '2.20260901.01.00')]:
    for endpoint, query in [('browse', {'browseId': 'FEwhat_to_watch'}),
                            ('search', {'query': 'science technology'}),
                            ('browse', {'browseId': 'FEtrending'})]:
        payload = dict(query, context={'client': {'clientName': client, 'clientVersion': version, 'hl': 'en', 'gl': 'US'}})
        request = urllib.request.Request('https://www.youtube.com/youtubei/v1/' + endpoint + '?prettyPrint=false',
            data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json',
                'User-Agent': 'Mozilla/5.0', 'X-Youtube-Client-Name': number,
                'X-Youtube-Client-Version': version, 'Origin': 'https://www.youtube.com'})
        label = f'{client} {endpoint} {next(iter(query.values()))}'
        try:
            with urllib.request.urlopen(request, timeout=8) as response:
                root = json.load(response)
                print(f'::notice::{label}: HTTP {response.status}, video renderers={count_videos(root)}, api_error={root.get("error", {}).get("code", "none")}')
        except Exception as error:
            print(f'::notice::{label}: {type(error).__name__}: {error}')
print('Diagnostic only. Shared runner IPs and device networks can receive different responses.')
