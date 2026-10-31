# Supply placement waits for arrival

Clearing collision with a container is not the same as reaching the intended placement approach. Placement waits for the walking helper to reach a fixed approach-side destination with enough supported separation. Single and paired containers share that geometry.

Pickup waits for a visible tracked drop, a restored container or bounded missing-container recovery. A remembered placement position and placementSent flag alone do not establish a server-placed container or a safe pickup destination.
