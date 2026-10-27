"""FIFO run queue with a max-parallel limit.

Default parallelism is 1: every Maven run writes to the repo's shared target/ folder
(surefire/allure/extent output), so two at once would overwrite each other's results.
Raise DASHBOARD_MAX_PARALLEL only for types that don't share output (e.g. API runs) — the
queue itself doesn't know which, so it's a deliberate admin setting, not automatic.
"""
import collections
import os
import threading

MAX_QUEUED = 20


class QueueFull(Exception):
    pass


class RunQueue:
    def __init__(self, execute, max_parallel=None):
        """execute(run_id) runs one job to completion (blocking); exceptions are swallowed here
        (execute is responsible for recording its own failure in status.json)."""
        self._execute = execute
        self.max_parallel = max(1, int(max_parallel or os.environ.get("DASHBOARD_MAX_PARALLEL", "1")))
        self._pending = collections.deque()
        self._active = set()
        self._cv = threading.Condition()
        self._stop = False
        self._threads = [threading.Thread(target=self._worker, daemon=True, name=f"run-worker-{i}")
                         for i in range(self.max_parallel)]
        for t in self._threads:
            t.start()

    def submit(self, run_id):
        with self._cv:
            if len(self._pending) >= MAX_QUEUED:
                raise QueueFull(f"queue is full ({MAX_QUEUED} waiting runs)")
            self._pending.append(run_id)
            self._cv.notify()
            return len(self._pending)

    def cancel_pending(self, run_id):
        """Remove a not-yet-started run. True if it was still waiting."""
        with self._cv:
            try:
                self._pending.remove(run_id)
                return True
            except ValueError:
                return False

    def position(self, run_id):
        with self._cv:
            try:
                return list(self._pending).index(run_id) + 1
            except ValueError:
                return 0

    def active_ids(self):
        with self._cv:
            return set(self._active) | set(self._pending)

    def shutdown(self):
        with self._cv:
            self._stop = True
            self._cv.notify_all()

    def _worker(self):
        while True:
            with self._cv:
                while not self._pending and not self._stop:
                    self._cv.wait()
                if self._stop:
                    return
                run_id = self._pending.popleft()
                self._active.add(run_id)
            try:
                self._execute(run_id)
            except Exception:  # noqa: BLE001 — a failing job must never kill the worker
                pass
            finally:
                with self._cv:
                    self._active.discard(run_id)
