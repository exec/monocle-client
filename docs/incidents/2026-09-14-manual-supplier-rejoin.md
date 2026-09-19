# Manual supplier rejoin used the old work position

The last recorded UnbansBot supply origin was X -82098 while active workers
had reached X -82992. Re-enabling the module called `resumeJob`, which checked
distance to that old origin and refused to resume beyond 12 blocks. The host
excludes detached suppliers from ordinary off-duty admission so their physical
containers remain protected; that also prevented this worker using ordinary
off-duty return. Its snapshot showed no active supply session or container.

0.7.46 lets a displaced, paused supplier retire an unused restock request and
enter the existing supplier-return path. It requires the original world, a live
crew, no host pause/cancellation, no active resource exchange, no tracked
container/drop, no pending block confirmations, and no physical cleanup. It
resumes movement before transitioning from Restock to Forward. Visible crew
models or a fresh host front then guide return; the host still admits the worker
and assigns duties. The old supply waypoint is not used as the return target.

Host Resume and module activation share this path. Supply ownership and recovery
records are not discarded. In-game testing of the new jar remains required;
automated highway-supply and crew checks plus jar packaging checks passed.
