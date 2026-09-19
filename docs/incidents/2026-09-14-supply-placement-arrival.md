# Supply placement clearance — 0.7.40

The previous clearance gate stopped requesting movement as soon as the player's
body stopped overlapping the container block. It did not wait for arrival at
the intended standing position. Its destination also crossed to the opposite
side of the container; paired ender chests had a separate, conflicting approach.

Placement now waits for the existing walking helper to report arrival at a
fixed approach-side destination, two blocks from the container center. With
the walking tolerance and normal player body width, this leaves at least one
full block of separation. Single and paired containers use the same helper.

Pickup previously fell back to walking onto the remembered container position
when no matching item entity was visible. It now waits for a visible tracked
drop, a restored container, or the existing missing-container retry policy.
This avoids walking into the next placement position based on an unconfirmed
placement attempt. `placementSent` alone does not prove server placement.

Checks: highwaySupplyCheck, highwayBuilderCheck, swarmCrewCheck, jar,
bundledLibrariesCheck. Geometry checks cover every highway heading, intermediate
positions beyond collision clearance, and worst-case walking arrival tolerance.
These checks do not substitute for a live server test. The test job was paused
before this change; update all workers before resuming it.
