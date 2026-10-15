"""Expose Macrobenchmark JSON metrics through the accessible Checks API.

CPU duration and frame-deadline overrun are distinct metrics. Never turn a passing test
into a claim that frame times are acceptable, or an emulator run into phone certification.
"""
import json
import pathlib
import sys


def escape(text):
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


found = 0
for path in pathlib.Path(sys.argv[1]).rglob('*.json'):
    try:
        data = json.loads(path.read_text())
    except (ValueError, OSError):
        continue
    if not isinstance(data, dict):
        continue
    for benchmark in data.get('benchmarks', []):
        found += 1
        lines = [f"RELEASE PERF: {benchmark.get('name')} (minified target; emulator)"]
        metrics = {**benchmark.get('metrics', {}), **benchmark.get('sampledMetrics', {})}
        for name, metric in metrics.items():
            values = []
            def flatten(value):
                if isinstance(value, list):
                    for item in value:
                        flatten(item)
                elif isinstance(value, (int, float)):
                    values.append(value)
            flatten(metric.get('runs', []))
            values.sort()
            if values:
                def percentile(p):
                    return values[int((len(values) - 1) * p)]
                lines.append(f"{name}: n={len(values)}, p50={percentile(.5):.3f}, "
                             f"p95={percentile(.95):.3f}, p99={percentile(.99):.3f}")
            else:
                lines.append(f"{name}: {json.dumps(metric)[:500]}")
        print('::notice::' + escape('\n'.join(lines)))
if not found:
    print('::warning::No Macrobenchmark JSON results found; no performance conclusion is available.')
