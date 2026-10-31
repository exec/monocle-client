# Initial shulker-only restock deadlock

Requiring positive road progress before another supplier detaches creates a circular wait when every worker starts with supplies inside shulkers. Detachment must be possible at the initial checkpoint.

Footing checks must use the actual supported footprint rather than collision padding that extends into an adjacent unsupported cell. Collision margins remain separate. Early grouped pit-stop behavior was superseded by independent restocking; see the independent-restocking note.

Tests exercise simultaneous initial requests, supported edge geometry and container ownership. Geometry regressions do not establish that every historical route blocker had the same cause.
