# Completed deposits awaiting host receipts

A completed native inventory move and the host's durable catalog acknowledgement are separate events. Waiting for the latter must not resend the destructive inventory click or discard confirmed local receipts.

Retain stable receipt identity and destination evidence across bounded acknowledgement retries and reconnects. Duplicate delivery is idempotent; stale or wrong-execution acknowledgements cannot advance the action. Unknown host persistence must remain distinguishable from unconfirmed physical movement.
