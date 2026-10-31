# Restored supplier synchronization

Restoring an assignment front without its freshness timestamp makes the existing expiry check reject it immediately. Requiring Forward during a restored Center state can create another circular rejoin wait.

Restoration stamps the received front. Rejoining accepts Center or Forward only after normal restock, cursor, footing and cleanup checks. Completion establishes the work origin and Forward state; supply states cannot bypass cleanup.

Diagnostics retain the actual missing-destination gate. Socket tests require repeated fresh front updates while suppliers remain detached and admit only ready workers. Unknown donor or host behavior must not be inferred from a generic synchronization label.
