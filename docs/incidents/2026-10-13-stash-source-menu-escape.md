# External menu closure during pickup

An externally closed source menu can leave an old menu ID that rejects its replacement indefinitely. Source closure and idle replacement share snapshot invalidation.

With no pending transfer and an empty current cursor, forget the old menu, snapshot and opening marker, close an unrelated replacement and request the intended source afresh. Late packets from the old menu cannot satisfy the opening.

Confirmed receipts and source selection survive. Pending single or bulk transfers retain read-only reconciliation instead of replay. Tests cover Escape, replaced IDs, stale contents and pending-operation exclusion.
