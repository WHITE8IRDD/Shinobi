import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


class BenchmarkSummaryTest(unittest.TestCase):
    def test_sampled_frame_metrics_and_ordinary_metrics_are_both_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            pathlib.Path(directory, 'benchmark.json').write_text(json.dumps({'benchmarks': [{
                'name': 'fixture',
                'metrics': {'durationMs': {'runs': [1, 2, 3]}},
                'sampledMetrics': {'frameOverrunMs': {'runs': [[-2, 1], [10, 20]]}},
            }]}))
            script = pathlib.Path(__file__).resolve().parents[1] / 'summarize_benchmarks.py'
            result = subprocess.run([sys.executable, str(script), directory], check=True, capture_output=True, text=True)
            self.assertIn('durationMs: n=3', result.stdout)
            self.assertIn('frameOverrunMs: n=4', result.stdout)
            self.assertIn('p50=1.000', result.stdout)
            self.assertNotIn('No Macrobenchmark', result.stdout)


if __name__ == '__main__':
    unittest.main()
