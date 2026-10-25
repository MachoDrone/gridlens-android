"""Exact public node/GPU verification and bounded timeline export; synthetic only."""
import importlib.util
import json
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from test_gpu_collector import xml, GPU, AT

SOURCE = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/gpu-collector/node_gpu.py'
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('node_gpu', SOURCE)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
ADDRESS = '1' * 32
OTHER = '1' * 31 + '2'
OTHER_GPU = 'GPU-00000000-1111-2222-3333-555555555555'


def info(address=ADDRESS, gpu=GPU):
    return {'node': address, 'state': 'RUNNING_JOB', 'info': {'gpus': {'devices': [{'uuid': gpu}]}}}


class BindingTests(unittest.TestCase):
    def test_local_api_has_total_deadline_and_restores_signal_handler(self):
        class SlowConnection:
            def __init__(self, *args, **kwargs): pass
            def request(self, *args, **kwargs): pass
            def getresponse(self): time.sleep(8)
            def close(self): pass
        prior = signal.getsignal(signal.SIGALRM)
        started = time.monotonic()
        with patch.object(collector.http.client, 'HTTPConnection', SlowConnection):
            with self.assertRaises(TimeoutError):
                collector.node_json('/node/info')
        self.assertLess(time.monotonic() - started, 5)
        self.assertEqual(signal.getsignal(signal.SIGALRM), prior)
        self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0.0, 0.0))

    def test_exact_owner_api_uuid_and_actual_sample_must_agree(self):
        fetch = lambda path: ADDRESS if path == '/' else info()
        readings, status = collector.observe_node(ADDRESS, [GPU], AT, fetch, lambda: xml())
        self.assertEqual(status, 'ok')
        self.assertEqual(readings[0]['uuid'], GPU)
        for wrong in [lambda path: OTHER if path == '/' else info(),
                      lambda path: ADDRESS if path == '/' else info(OTHER),
                      lambda path: ADDRESS if path == '/' else info(gpu=OTHER_GPU)]:
            readings, status = collector.observe_node(ADDRESS, [GPU], AT, wrong, lambda: self.fail('No GPU query after invalid identity'))
            self.assertEqual((readings, status), ([], 'binding-mismatch'))
        self.assertEqual(collector.observe_node(ADDRESS, [GPU], AT, fetch, lambda: xml(uuid=OTHER_GPU)), ([], 'binding-mismatch'))

    def test_identity_transition_during_gpu_query_discards_sample(self):
        requests = []
        def fetch(path):
            requests.append(path)
            if path == '/':
                return ADDRESS if len(requests) == 1 else OTHER
            return info()
        self.assertEqual(collector.observe_node(ADDRESS, [GPU], AT, fetch, lambda: xml()), ([], 'binding-mismatch'))

    def test_multiple_gpus_are_explicit_no_gpu_order_or_job_identity_inference(self):
        value = info()
        value['info']['gpus']['devices'].append({'uuid': OTHER_GPU})
        fetch = lambda path: ADDRESS if path == '/' else value
        self.assertTrue(collector.verify_node(ADDRESS, [OTHER_GPU, GPU], fetch))
        self.assertFalse(collector.verify_node(ADDRESS, [GPU], fetch))
        with self.assertRaises(ValueError):
            collector.canonical_uuids([GPU, GPU.lower().replace('gpu-', 'GPU-')])


class TimelineTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / 'node-gpu.sqlite'
        self.db = collector.open_database(self.path)

    def tearDown(self):
        self.db.close()
        self.directory.cleanup()

    def add(self, at, count='100 us', flag='Active'):
        collector.record(self.db, collector.parse_nvidia_xml(xml(count=count, flag=flag), at), at)

    def test_compact_timeline_preserves_supported_false_unknown_and_counters(self):
        self.add(AT)
        self.add(AT + 15000, '200 us', 'Not Active')
        result = collector.node_summary(self.db, ADDRESS, [GPU], AT + 15001)
        self.assertEqual(result['schema'], 2)
        self.assertEqual(result['samples'], [[AT, 0, 3, 1, 100, None, None, None],
                                             [AT + 15000, 0, 3, 0, 200, None, None, None]])
        self.assertTrue(result['clockTrusted'])
        self.assertNotIn('job', json.dumps(result))

    def test_incremental_cursor_keeps_original_sample_times_and_is_bounded(self):
        for offset in [0, 15000, 30000]:
            self.add(AT + offset)
        result = collector.node_summary(self.db, ADDRESS, [GPU], AT + 30001, AT + 15000)
        self.assertEqual(result['fromAtMs'], AT + 15000)
        self.assertEqual([row[0] for row in result['samples']], [AT + 15000, AT + 30000])
        self.assertEqual(result['observedAtMs'], AT + 30000)
        for cursor in [-1, True, AT + 40000]:
            with self.assertRaises(ValueError):
                collector.node_summary(self.db, ADDRESS, [GPU], AT + 30001, cursor)

    def test_export_truncation_is_explicit_and_does_not_invent_coverage(self):
        for i in range(5):
            self.add(AT + i * 15000)
        with patch.object(collector, 'MAX_EXPORT_SAMPLES', 3):
            result = collector.node_summary(self.db, ADDRESS, [GPU], AT + 60001)
        self.assertTrue(result['truncated'])
        self.assertEqual([row[0] for row in result['samples']], [AT + 30000, AT + 45000, AT + 60000])
        self.assertEqual(self.db.execute('SELECT count(*) FROM samples').fetchone()[0], 5)

    def test_mismatch_failure_and_clock_discontinuity_keep_history_honest(self):
        self.add(AT)
        collector.record(self.db, [], AT + 15000, 'binding-mismatch')
        result = collector.node_summary(self.db, ADDRESS, [GPU], AT + 15001)
        self.assertEqual(result['status'], 'binding-mismatch')
        self.assertEqual(result['samples'][0][0], AT)
        collector.record(self.db, [], AT + 30000, 'clock-untrusted')
        result = collector.node_summary(self.db, ADDRESS, [GPU], AT + 30001)
        self.assertFalse(result['clockTrusted'])
        self.assertEqual(result['samples'][0][0], AT)
        with self.assertRaises(ValueError):
            collector.node_summary(self.db, ADDRESS, [OTHER_GPU], AT + 30001)

    def test_missing_startup_and_stale_recorder_are_not_healthy_zero(self):
        result = collector.node_summary(self.db, ADDRESS, [GPU], AT)
        self.assertEqual(result['status'], 'starting')
        self.assertFalse(result['clockTrusted'])
        self.assertIsNone(result['observedAtMs'])
        self.assertEqual(result['samples'], [])
        self.add(AT)
        self.assertEqual(collector.node_summary(self.db, ADDRESS, [GPU], AT + 60000)['status'], 'stale')

    def test_running_process_records_without_phone_reads_and_reopens_history(self):
        script = '''import importlib.util,sys
from pathlib import Path
s=importlib.util.spec_from_file_location('fixture_node_gpu',sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)
m.BOUND_ADDRESS=sys.argv[3]; m.BOUND_UUIDS=[sys.argv[4]]; m.DATABASE=Path(sys.argv[2]); m.INTERVAL_SECONDS=0.1
query=lambda: bytes.fromhex(sys.argv[5])
fetch=lambda path: m.BOUND_ADDRESS if path=='/' else {'node':m.BOUND_ADDRESS,'info':{'gpus':{'devices':[{'uuid':m.BOUND_UUIDS[0]}]}}}
observe=m.observe_node; m.observe_node=lambda a,u,at,**kwargs: observe(a,u,at,fetch,query,**kwargs)
sys.exit(m.node_main(['run']))
'''
        self.db.close()
        process = subprocess.Popen([sys.executable, '-B', '-c', script, str(SOURCE), str(self.path), ADDRESS, GPU, xml().hex()], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            time.sleep(1.1)
            self.assertIsNone(process.poll())
        finally:
            process.terminate()
            _, error = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, error)
        self.db = collector.open_database(self.path, readonly=True)
        result = collector.node_summary(self.db, ADDRESS, [GPU], time.time_ns() // 1000000)
        self.assertGreaterEqual(len(result['samples']), 2)


if __name__ == '__main__':
    unittest.main()
