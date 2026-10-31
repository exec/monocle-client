# Withdrawal bursts and unread sources

Quick-move batching is only safe when a reconciled baseline and deterministic capacity support the intended changes. Dynamic hopper sources require exact confirmation and a fallback when source contents move between clicks.

Retain per-move receipts and bounded source deferral; an unread or unverified source cannot be declared exhausted. Separate approach/read deadlines from pending inventory confirmation so a source timeout cannot replay an uncertain burst. Physical server ordering still requires gameplay tests in addition to offline inventory checks.
