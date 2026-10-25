"""Synthetic GPU XML and isolated owned-volume history; no physical GPUs/hosts."""
import importlib.util
import json
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/gpu-collector/collector.py'
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('gpu_collector', SOURCE)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
GPU = 'GPU-00000000-1111-2222-3333-444444444444'
AT = 1800000000000


def xml(flag='Active', count='100 us', legacy=False, uuid=GPU):
    family = 'throttle' if legacy else 'event'
    return (f'<?xml version="1.0"?>\n<!DOCTYPE nvidia_smi_log SYSTEM "nvsmi_device_v13.dtd">'
            f'<nvidia_smi_log><gpu><uuid>{uuid}</uuid><minor_number>7</minor_number>'
            f'<clocks_{family}_reasons>'
            f'<clocks_{family}_reason_sw_thermal_slowdown>{flag}</clocks_{family}_reason_sw_thermal_slowdown>'
            f'<clocks_{family}_reason_hw_thermal_slowdown>Not Active</clocks_{family}_reason_hw_thermal_slowdown>'
            f'<clocks_{family}_reason_sw_power_cap>N/A</clocks_{family}_reason_sw_power_cap>'
            f'</clocks_{family}_reasons><clocks_event_reasons_counters>'
            f'<clocks_event_reasons_counters_sw_therm_slowdown>{count}</clocks_event_reasons_counters_sw_therm_slowdown>'
            '</clocks_event_reasons_counters></gpu></nvidia_smi_log>').encode()


class ParserTests(unittest.TestCase):
    def test_modern_reasons_keep_false_distinct_from_unsupported(self):
        reading = collector.parse_nvidia_xml(xml(), AT)[0]
        self.assertEqual(reading['reasons'], {'swThermalSlowdown': True, 'hwThermalSlowdown': False,
                                            'swPowerCap': None, 'hwPowerBrake': None})
        self.assertEqual(reading['durationUs']['swThermalSlowdown'], 100)
        self.assertIsNone(reading['durationUs']['swPowerCap'])
        self.assertEqual(reading['observedAtMs'], AT)
        self.assertIsNone(reading['index'])  # Device minor/order is not an index proof.
        self.assertEqual(reading['deviceMinor'], 7)
        self.assertEqual(reading['uuid'], GPU)

    def test_older_driver_reasons_supported_without_invented_counters(self):
        reading = collector.parse_nvidia_xml(xml(legacy=True, count='N/A'), AT)[0]
        self.assertTrue(reading['reasons']['swThermalSlowdown'])
        self.assertTrue(all(value is None for value in reading['durationUs'].values()))

    def test_only_exact_supported_values_become_measurements(self):
        for flag in ['N/A', 'Unsupported', 'false', '0', 'Not active', '']:
            self.assertIsNone(collector.parse_nvidia_xml(xml(flag=flag), AT)[0]['reasons']['swThermalSlowdown'])
        for count in ['-1 us', '1.5 us', '100 ms', str(1 << 53), 'NaN', 'Not Supported']:
            self.assertIsNone(collector.parse_nvidia_xml(xml(count=count), AT)[0]['durationUs']['swThermalSlowdown'])

    def test_entity_expansion_external_dtd_wrong_root_and_oversize_rejected(self):
        cases = [b'<!DOCTYPE x [<!ENTITY a "expanded">]><nvidia_smi_log/>',
                 b'<!DOCTYPE nvidia_smi_log SYSTEM "file:///etc/passwd"><nvidia_smi_log/>',
                 b'<x/>', b' ' * (collector.MAX_XML_BYTES + 1), b'\xff']
        for case in cases:
            with self.assertRaises((ValueError, UnicodeError)):
                collector.parse_nvidia_xml(case, AT)

    def test_duplicate_and_invalid_gpu_identity_rejected(self):
        with self.assertRaises(ValueError):
            collector.parse_nvidia_xml(xml(uuid='not-a-gpu'), AT)
        payload = xml().replace(b'</gpu>', b'<uuid>' + GPU.encode() + b'</uuid></gpu>')
        with self.assertRaises(ValueError):
            collector.parse_nvidia_xml(payload, AT)
        payload = xml().replace(b'</nvidia_smi_log>', xml().split(b'<nvidia_smi_log>')[1])
        with self.assertRaises(ValueError):
            collector.parse_nvidia_xml(payload, AT)

    def test_unreported_index_never_inferred_from_order(self):
        payload = xml().replace(b'<minor_number>7</minor_number>', b'')
        reading = collector.parse_nvidia_xml(payload, AT)[0]
        self.assertIsNone(reading['index'])
        self.assertIsNone(reading['deviceMinor'])


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / 'gpu.sqlite'
        self.db = collector.open_database(self.path)

    def tearDown(self):
        self.db.close()
        self.directory.cleanup()

    def add(self, at, count='100 us', flag='Active'):
        collector.record(self.db, collector.parse_nvidia_xml(xml(flag=flag, count=count), at), at)

    def test_reopen_preserves_original_observations_and_read_is_not_a_sample(self):
        self.add(AT)
        self.db.close()
        self.db = collector.open_database(self.path, readonly=True)
        result = collector.summarize(self.db, AT + 60000)
        self.assertEqual(result['collector']['lastSuccessAtMs'], AT)
        self.assertEqual(result['collector']['status'], 'stale')
        self.assertEqual(result['gpus'][0]['observedAtMs'], AT)
        self.assertEqual(result['gpus'][0]['history']['sampleCount'], 1)
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(sqlite3.OperationalError):
            self.db.execute('DELETE FROM samples')

    def test_counter_deltas_require_continuity_no_reset_and_supported_values(self):
        self.add(AT, '100 us')
        self.add(AT + 15000, '400 us', flag='Not Active')
        self.add(AT + 30000, '10 us')  # Counter reset.
        self.add(AT + 90000, '500 us')  # Unobserved gap is not measured duration.
        result = collector.summarize(self.db, AT + 90001)['gpus'][0]['history']
        thermal = result['reasons']['swThermalSlowdown']
        self.assertEqual(thermal, {'supportedSamples': 4, 'activeSamples': 3,
                                 'counterObservedUs': 300, 'counterIntervals': 1, 'counterResets': 1})
        self.assertEqual(result['maxGapMs'], 60000)
        self.assertIsNone(result['reasons']['swPowerCap']['counterObservedUs'])

    def test_failure_preserves_history_and_does_not_become_false_or_fresh(self):
        self.add(AT)
        collector.record(self.db, [], AT + 15000, 'query-failed')
        result = collector.summarize(self.db, AT + 15000)
        self.assertEqual(result['collector']['status'], 'query-failed')
        self.assertEqual(result['collector']['lastAttemptAtMs'], AT + 15000)
        self.assertEqual(result['collector']['lastSuccessAtMs'], AT)
        self.assertEqual(result['gpus'][0]['observedAtMs'], AT)

    def test_clock_regression_never_creates_fresh_observation(self):
        self.add(AT)
        self.add(AT - 10000)
        result = collector.summarize(self.db, AT - 10000)
        self.assertEqual(result['collector']['status'], 'clock-regressed')
        self.assertEqual(result['collector']['lastSuccessAtMs'], AT)
        self.assertEqual(result['gpus'], [])
        self.assertEqual(self.db.execute('SELECT count(*) FROM samples').fetchone()[0], 1)

    def test_forward_clock_jump_freezes_samples_and_pruning_until_clock_restored(self):
        self.add(AT)
        guard = collector.ClockGuard()
        self.assertTrue(guard.observe(AT, 1000))
        jumped = AT + 8 * 86400000
        self.assertFalse(guard.observe(jumped, 16000))
        collector.record(self.db, collector.parse_nvidia_xml(xml(), jumped), jumped, 'clock-untrusted')
        self.assertEqual(self.db.execute('SELECT count(*) FROM samples').fetchone()[0], 1)
        self.assertEqual(self.db.execute('SELECT succeeded FROM metadata').fetchone()[0], AT)
        self.assertFalse(guard.observe(jumped + 15000, 31000))  # Repeated error cannot reset anchor.
        self.assertTrue(guard.observe(AT + 45000, 46000))
        self.add(AT + 45000)
        result = collector.summarize(self.db, AT + 45000)
        self.assertEqual(result['collector']['status'], 'ok')
        self.assertEqual(result['gpus'][0]['history']['sampleCount'], 2)
        self.assertEqual(result['gpus'][0]['history']['firstObservedAtMs'], AT)

    def test_monotonic_clock_guards_backward_corrections_and_accepts_elapsed_suspend(self):
        guard = collector.ClockGuard()
        self.assertTrue(guard.observe(AT, 1000))
        self.assertFalse(guard.observe(AT - 60000, 16000))
        self.assertFalse(guard.observe(AT + 30000, 500))
        self.assertTrue(guard.observe(AT + 3600000, 3601000))  # Linux boottime includes suspend.

    def test_retention_and_capacity_do_not_claim_missing_coverage(self):
        self.add(AT - 8 * 86400000)
        with patch.object(collector, 'MAX_RECORDS', 3):
            for i in range(5):
                self.add(AT + i * 15000)
            result = collector.summarize(self.db, AT + 60000)['gpus'][0]['history']
            self.assertEqual(result['sampleCount'], 3)
            self.assertEqual(result['firstObservedAtMs'], AT + 30000)
        self.assertEqual(self.db.execute('PRAGMA max_page_count').fetchone()[0], 4096)

    def test_explicit_window_excludes_other_jobs_and_does_not_claim_job_identity(self):
        self.add(AT)
        self.add(AT + 15000)
        self.add(AT + 30000)
        result = collector.summarize(self.db, AT + 30000, AT + 10000, AT + 20000)
        self.assertEqual(result['gpus'][0]['history']['sampleCount'], 1)
        self.assertNotIn('job', json.dumps(result).lower())
        with self.assertRaises(ValueError):
            collector.summarize(self.db, AT, AT - 8 * 86400000, AT)
        with self.assertRaises(ValueError):
            collector.summarize(self.db, AT, AT, AT + 1)

    def test_unknown_database_version_is_preserved_and_refused(self):
        self.db.execute('PRAGMA user_version=7')
        self.db.commit()
        with self.assertRaises(ValueError):
            collector.open_database(self.path)
        self.assertEqual(self.db.execute('PRAGMA user_version').fetchone()[0], 7)

    def test_partial_recording_rolls_back(self):
        self.add(AT)
        good = collector.parse_nvidia_xml(xml(), AT + 15000)[0]
        malformed = {**good, 'extra': 'x' * 3000}
        with self.assertRaises(ValueError):
            collector.record(self.db, [good, malformed], AT + 15000)
        result = collector.summarize(self.db, AT + 15000)
        self.assertEqual(result['gpus'][0]['history']['sampleCount'], 1)
        self.assertEqual(result['collector']['lastSuccessAtMs'], AT)

    def test_recorder_process_samples_without_a_reader_and_stops_cleanly(self):
        # Run the actual loop in a separate process with an injected synthetic
        # NVIDIA response. No phone connection or history reader drives it.
        script = ('import importlib.util,sys; from pathlib import Path; '
                  'sys.dont_write_bytecode=True; '
                  's=importlib.util.spec_from_file_location("c",sys.argv[1]); '
                  'c=importlib.util.module_from_spec(s); s.loader.exec_module(c); '
                  'c.DATABASE=Path(sys.argv[2]); c.INTERVAL_SECONDS=0.1; '
                  'c.query_nvidia=lambda: sys.argv[3].encode(); c.main(["run"])')
        process = subprocess.Popen([sys.executable, '-B', '-c', script, str(SOURCE), str(self.path), xml().decode()],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if self.db.execute('SELECT count(*) FROM samples').fetchone()[0] >= 2:
                    break
                time.sleep(0.05)
            self.assertGreaterEqual(self.db.execute('SELECT count(*) FROM samples').fetchone()[0], 2)
        finally:
            process.terminate()
            output, error = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, error.decode())
        self.assertEqual(output, b'')
        self.assertEqual(error, b'')
        count = self.db.execute('SELECT count(*) FROM samples').fetchone()[0]
        # A second connection reopening the owned volume observes durable data.
        reopened = collector.open_database(self.path, readonly=True)
        try:
            self.assertEqual(reopened.execute('SELECT count(*) FROM samples').fetchone()[0], count)
        finally:
            reopened.close()


if __name__ == '__main__':
    unittest.main()
