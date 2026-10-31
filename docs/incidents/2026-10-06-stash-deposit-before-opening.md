# Deposit packets before destination opening

Inventory callbacks can arrive while an action is waiting for home cooldown, travel or column approval. Accepting them before a destination target and requested opening exist can dereference a null target or bind stale source contents as a deposit menu.

Observation requires a selected destination, requested opening and no pending host-acknowledgement wait. The first destination menu remains bound across clicks. Closing or replacing an idle menu clears its opening marker and baseline before reopening.

Pending moves retain position, timeouts and inspection instead of replay. Packet-level tests cover every pre-opening phase, acknowledgement waits, menu replacement and restoration.
