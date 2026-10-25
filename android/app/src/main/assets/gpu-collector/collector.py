"""GridLens GPU recorder. Fixed, read-only NVIDIA query; owned-volume history only.

Run in the labeled, network-isolated GridLens Docker container. The phone reads
summaries through SSH/docker exec. No keys, Docker socket, host bind mounts,
network access, GPU settings, workload control, or job-attribution guesses.
Observation times use the PC clock. During a recorder process, discontinuities
against monotonic time suspend samples/pruning until the clock is coherent again.
After process/host restart, backward movement behind the last stored sample is
rejected, but a newly advanced clock cannot be independently authenticated.
"""
import json
import os
import re
import selectors
import signal
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA = 1
INTERVAL_SECONDS = 15
RETENTION_DAYS = 7
MAX_RECORDS = 20000
MAX_GPUS = 64
MAX_XML_BYTES = 2 * 1024 * 1024
MAX_READ_BYTES = 256 * 1024
DATABASE = Path('/data/gpu.sqlite')
REASONS = {
    'swThermalSlowdown': ('sw_thermal_slowdown', 'sw_therm_slowdown'),
    'hwThermalSlowdown': ('hw_thermal_slowdown', 'hw_therm_slowdown'),
    'swPowerCap': ('sw_power_cap', 'sw_power_cap'),
    'hwPowerBrake': ('hw_power_brake_slowdown', 'hw_power_brake'),
}
UUID = re.compile(r'GPU-[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}')
MAX_SAFE_INTEGER = (1 << 53) - 1
ALLOWED_STATUS = {'ok', 'unsupported', 'query-failed', 'invalid-response', 'clock-regressed', 'clock-untrusted'}


class ClockGuard:
    """Freeze the last coherent anchor across a wall-clock discontinuity."""
    def __init__(self):
        self.wall = None
        self.monotonic = None

    def observe(self, wall_ms, monotonic_ms):
        if self.wall is not None:
            elapsed = monotonic_ms - self.monotonic
            if elapsed < 0 or abs((wall_ms - self.wall) - elapsed) > 5000:
                return False
        self.wall, self.monotonic = wall_ms, monotonic_ms
        return True


def monotonic_clock_ms():
    # Linux boottime includes host suspend, so a genuine sleep interval does not
    # look like a wall-clock correction. Fall back only outside that platform.
    if hasattr(time, 'CLOCK_BOOTTIME'):
        return time.clock_gettime_ns(time.CLOCK_BOOTTIME) // 1000000
    return time.monotonic_ns() // 1000000


def _one_text(element, path):
    values = element.findall(path)
    if len(values) > 1:
        raise ValueError('Duplicate NVIDIA field')
    return None if not values or values[0].text is None else values[0].text.strip()


def _active(value):
    return True if value == 'Active' else False if value == 'Not Active' else None


def _counter(value):
    if value is None or not re.fullmatch(r'(?:0|[1-9][0-9]{0,15})(?: us)?', value):
        return None
    number = int(value.removesuffix(' us'))
    return number if number <= MAX_SAFE_INTEGER else None


def parse_nvidia_xml(raw, observed_at_ms):
    """Keep only supported reason flags/counters and explicit physical identity."""
    if not isinstance(raw, bytes) or len(raw) > MAX_XML_BYTES:
        raise ValueError('Invalid NVIDIA response size')
    # nvidia-smi normally declares its bundled DTD. Never resolve it, accept an
    # internal subset, or expand attacker-controlled entity declarations.
    text = raw.decode('utf-8', errors='strict')
    text = re.sub(r'<!DOCTYPE nvidia_smi_log SYSTEM "nvsmi_device_v[0-9]+\.dtd">', '', text)
    if '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper():
        raise ValueError('Unsupported XML declaration')
    root = ET.fromstring(text)
    if root.tag != 'nvidia_smi_log':
        raise ValueError('Invalid NVIDIA root')
    elements = root.findall('gpu')
    if len(elements) > MAX_GPUS:
        raise ValueError('Too many GPUs')
    result, seen = [], set()
    for gpu in elements:
        uuid = _one_text(gpu, 'uuid')
        if uuid is None or UUID.fullmatch(uuid) is None or uuid.lower() in seen:
            raise ValueError('Invalid or duplicate GPU identity')
        seen.add(uuid.lower())
        # XML order is not proof of nvidia-smi's physical GPU index.
        raw_index = _one_text(gpu, 'minor_number')
        minor = int(raw_index) if raw_index is not None and re.fullmatch(r'[0-9]{1,4}', raw_index) else None
        reasons, counters = {}, {}
        for name, (flag_name, counter_name) in REASONS.items():
            modern = _one_text(gpu, 'clocks_event_reasons/clocks_event_reason_' + flag_name)
            legacy = _one_text(gpu, 'clocks_throttle_reasons/clocks_throttle_reason_' + flag_name)
            if modern is not None and legacy is not None and modern != legacy:
                raise ValueError('Conflicting NVIDIA flags')
            reasons[name] = _active(modern if modern is not None else legacy)
            counters[name] = _counter(_one_text(gpu, 'clocks_event_reasons_counters/clocks_event_reasons_counters_' + counter_name))
        result.append({'uuid': uuid.lower().replace('gpu-', 'GPU-', 1), 'index': None, 'deviceMinor': minor,
                       'observedAtMs': observed_at_ms, 'reasons': reasons, 'durationUs': counters})
    return result


def query_nvidia():
    """Fixed argv, bounded output/time, and no shell or raw error export."""
    executable = '/usr/bin/nvidia-smi'
    if not os.path.isfile(executable):
        # NVIDIA's container runtime may inject utility binaries here instead.
        executable = '/usr/local/nvidia/bin/nvidia-smi'
    if not os.path.isfile(executable):
        raise FileNotFoundError('NVIDIA utility unavailable')
    process = subprocess.Popen([executable, '-q', '-x'], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               env={'PATH': '/usr/bin:/usr/local/nvidia/bin',
                                    'LD_LIBRARY_PATH': '/usr/local/nvidia/lib:/usr/local/nvidia/lib64'},
                               start_new_session=True)
    chunks, total, deadline = [], 0, time.monotonic() + 8
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                if time.monotonic() >= deadline:
                    raise TimeoutError('NVIDIA query timed out')
                for key, _ in selector.select(min(0.1, max(0, deadline - time.monotonic()))):
                    chunk = os.read(key.fd, 16384)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    total += len(chunk)
                    if total > MAX_XML_BYTES:
                        raise ValueError('NVIDIA output too large')
                    chunks.append(chunk)
        if process.wait(timeout=max(0.01, deadline - time.monotonic())) != 0:
            raise OSError('NVIDIA query failed')
        return b''.join(chunks)
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        process.stdout.close()


def open_database(path=None, readonly=False):
    path = DATABASE if path is None else Path(path)
    if path.is_symlink():
        raise ValueError('Database symlink is not permitted')
    if not readonly:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    connection = sqlite3.connect(path.as_uri() + ('?mode=ro' if readonly else '?mode=rwc'), uri=True, timeout=3)
    connection.execute('PRAGMA trusted_schema=OFF')
    connection.execute('PRAGMA temp_store=MEMORY')
    version = connection.execute('PRAGMA user_version').fetchone()[0]
    if version not in (0, SCHEMA) or (readonly and version != SCHEMA):
        connection.close()
        raise ValueError('Unsupported database schema')
    if not readonly:
        connection.execute('PRAGMA journal_mode=DELETE')
        connection.execute('PRAGMA synchronous=FULL')
        connection.execute('PRAGMA page_size=4096')
        connection.execute('PRAGMA max_page_count=4096')  # 16 MiB, plus bounded transaction journal.
        connection.execute('CREATE TABLE IF NOT EXISTS samples (id INTEGER PRIMARY KEY, at INTEGER NOT NULL, uuid TEXT NOT NULL, payload TEXT NOT NULL)')
        connection.execute('CREATE INDEX IF NOT EXISTS samples_at ON samples(at)')
        connection.execute('CREATE TABLE IF NOT EXISTS metadata (id INTEGER PRIMARY KEY CHECK(id=1), status TEXT NOT NULL, attempted INTEGER NOT NULL, succeeded INTEGER)')
        connection.execute('PRAGMA user_version=1')
        connection.commit()
        os.chmod(path, 0o600)
    return connection


def record(connection, readings, observed_at_ms, status='ok'):
    if status not in ALLOWED_STATUS or len(readings) > MAX_GPUS:
        raise ValueError('Invalid recording')
    previous = connection.execute('SELECT succeeded FROM metadata WHERE id=1').fetchone()
    previous_success = previous[0] if previous else None
    if previous_success is not None and observed_at_ms <= previous_success:
        status, readings = 'clock-regressed', []
    if status in ('clock-regressed', 'clock-untrusted'):
        readings = []
    succeeded = observed_at_ms if status == 'ok' else previous_success
    with connection:
        # Untrusted clocks must not erase recorded history with a premature age
        # cutoff. Ordinary query failures still permit bounded age retention.
        if status not in ('clock-regressed', 'clock-untrusted'):
            connection.execute('DELETE FROM samples WHERE at < ?', (observed_at_ms - RETENTION_DAYS * 86400000,))
        overflow = max(0, connection.execute('SELECT count(*) FROM samples').fetchone()[0] + len(readings) - MAX_RECORDS)
        if overflow:
            connection.execute('DELETE FROM samples WHERE id IN (SELECT id FROM samples ORDER BY id LIMIT ?)', (overflow,))
        for reading in readings:
            payload = json.dumps(reading, separators=(',', ':'), allow_nan=False)
            if len(payload) > 2048:
                raise ValueError('Reading too large')
            connection.execute('INSERT INTO samples(at,uuid,payload) VALUES(?,?,?)', (observed_at_ms, reading['uuid'], payload))
        connection.execute('INSERT OR REPLACE INTO metadata VALUES(1,?,?,?)', (status, observed_at_ms, succeeded))


def summarize(connection, now_ms, start_ms=None, end_ms=None):
    start_ms = now_ms - 86400000 if start_ms is None else start_ms
    end_ms = now_ms if end_ms is None else end_ms
    if not (isinstance(start_ms, int) and isinstance(end_ms, int) and 0 <= start_ms <= end_ms <= now_ms):
        raise ValueError('Invalid observation window')
    if end_ms - start_ms > RETENTION_DAYS * 86400000:
        raise ValueError('Observation window exceeds retention')
    metadata = connection.execute('SELECT status,attempted,succeeded FROM metadata WHERE id=1').fetchone()
    status, attempted, succeeded = metadata if metadata else ('starting', None, None)
    if attempted is not None and attempted > now_ms + 5000:
        status = 'clock-untrusted'
    elif attempted is not None and now_ms - attempted > INTERVAL_SECONDS * 3000:
        status = 'stale'
    result = {'schema': SCHEMA, 'collector': {'status': status,
              'lastAttemptAtMs': attempted, 'lastSuccessAtMs': succeeded,
              'intervalSeconds': INTERVAL_SECONDS, 'retentionDays': RETENTION_DAYS,
              'maxRecords': MAX_RECORDS}, 'window': {'startAtMs': start_ms, 'endAtMs': end_ms}, 'gpus': []}
    gpus, previous = {}, {}
    # Fixed capacity makes this read bounded even after the phone was away.
    for at, uuid, payload in connection.execute('SELECT at,uuid,payload FROM samples WHERE at>=? AND at<=? ORDER BY id LIMIT ?', (start_ms, end_ms, MAX_RECORDS)):
        sample = json.loads(payload)
        if uuid not in gpus:
            if len(gpus) == MAX_GPUS:
                continue
            gpus[uuid] = {'history': {'firstObservedAtMs': at, 'lastObservedAtMs': at,
                          'sampleCount': 0, 'maxGapMs': 0, 'reasons': {name: {
                              'supportedSamples': 0, 'activeSamples': 0,
                              'counterObservedUs': None, 'counterIntervals': 0,
                              'counterResets': 0} for name in REASONS}}}
        gpu = gpus[uuid]
        history = gpu['history']
        last = previous.get(uuid)
        gap = at - last['observedAtMs'] if last is not None else None
        if gap is not None:
            history['maxGapMs'] = max(history['maxGapMs'], gap)
        history['sampleCount'] += 1
        history['lastObservedAtMs'] = at
        for name in REASONS:
            entry = history['reasons'][name]
            active = sample['reasons'][name]
            entry['supportedSamples'] += int(active is not None)
            entry['activeSamples'] += int(active is True)
            current_counter = sample['durationUs'][name]
            old_counter = last['durationUs'][name] if last else None
            if current_counter is not None and old_counter is not None:
                delta = current_counter - old_counter
                if delta < 0:
                    entry['counterResets'] += 1
                elif gap is not None and 0 < gap <= INTERVAL_SECONDS * 2500 and delta <= gap * 2000:
                    entry['counterObservedUs'] = (entry['counterObservedUs'] or 0) + delta
                    entry['counterIntervals'] += 1
        gpu.update(sample)
        previous[uuid] = sample
    result['gpus'] = list(gpus.values())
    return result


def main(argv):
    if argv and argv[0] == 'read' and len(argv) in (1, 3):
        try:
            with open_database(readonly=True) as connection:
                now_ms = time.time_ns() // 1000000
                window = [] if len(argv) == 1 else [int(value) for value in argv[1:]]
                output = summarize(connection, now_ms, *window)
            encoded = json.dumps(output, separators=(',', ':'), allow_nan=False)
            if len(encoded) > MAX_READ_BYTES:
                raise ValueError('Summary too large')
            print(encoded)
            return 0
        except (ValueError, OSError, sqlite3.Error, KeyError, TypeError):
            print('{"schema":1,"collector":{"status":"unavailable"},"gpus":[]}')
            return 1
    if argv != ['run']:
        return 2
    stopping = False

    def stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    os.umask(0o077)
    clock = ClockGuard()
    with open_database() as connection:
        while not stopping:
            started = time.monotonic()
            observed_at_ms = time.time_ns() // 1000000
            try:
                if clock.observe(observed_at_ms, monotonic_clock_ms()):
                    readings = parse_nvidia_xml(query_nvidia(), observed_at_ms)
                    status = 'ok' if readings else 'unsupported'
                else:
                    readings, status = [], 'clock-untrusted'
            except FileNotFoundError:
                readings, status = [], 'unsupported'
            except (OSError, TimeoutError, subprocess.SubprocessError):
                readings, status = [], 'query-failed'
            except (ValueError, UnicodeError, ET.ParseError):
                readings, status = [], 'invalid-response'
            record(connection, readings, observed_at_ms, status)
            # Fixed lower request rate regardless of read calls or phone presence.
            while not stopping and time.monotonic() - started < INTERVAL_SECONDS:
                time.sleep(0.25)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv[1:]))
    except (OSError, ValueError, sqlite3.Error):
        print('GPU recorder unavailable', file=sys.stderr)
        sys.exit(1)
