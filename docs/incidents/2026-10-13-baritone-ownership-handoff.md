# Baritone ownership and deferred cancellation

isPathing excludes paused paths and initial calculations, so it is insufficient as an ownership gate. cancelEverything may return before a movement can safely stop; discarding the lease then can restore restrictions too early.

Worker movement tracks retained paths, calculations and accepted navigation processes. Deferred cleanup retains the lease and restrictions, forbids new goals and polls safe cancellation on client ticks. Worker steering and action preparation cannot pass the gate until cleanup completes.

Foreign replacement goals and unrelated processes prevent blanket cancellation. Behavioral tests exercise the real lease against simulated Baritone APIs and an event bus, including settings restoration and cancellation races. No forceCancel or Baritone modification is required.
