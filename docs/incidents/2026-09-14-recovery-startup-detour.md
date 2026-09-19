# Highway startup recovery detour

The 0.7.34 live test `e6ca74ad-1944-4559-ad9a-768fb5d7a275` never reached
its native Highway action. All three workers were executing `RecoverSupplies`
in the generated workflow wrapper. Two travelled from approximately X -52713
to X -52485/-52482 toward historical supply records; the third remained at
X -52713 reporting no supported walking route. The operator observed repeated
takeoffs and workers leapfrogging. Cancellation was acknowledged by all three;
their current executions and movement inputs cleared.

0.7.35 removes the unconditional historical-recovery action from generated native
highway workflows, shared by client hosting and standalone hosting. Explicit
Recover Supplies workflows remain available, and journal records are retained.
New native jobs enter Highway immediately and use native restocking. Existing
immutable job packages retain their old scripts and must be replaced to receive
the corrected startup sequence.

Explicit recovery also stopped and recreated its Travel action every `retryTicks`
(100 ticks by default), even while progressing. Inventory/mining evidence is now
refreshed without stopping that travel. Movement retries remain owned by Travel.

Regression coverage executes the generated Lua for every bundled native preset:
the first action must be Highway, the next Done. The standalone recovery workflow
must still dispatch RecoverSupplies. Host socket tests exercise the generated
workflow through native dispatch and cancellation. Physical flight remains a
live-client test after installing the new jar.
