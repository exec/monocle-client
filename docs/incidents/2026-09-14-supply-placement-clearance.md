# Supply placement clips the worker — 0.7.37

The westbound 0.7.36 test from X -81955 was paused at zero progress. The
operator clarified that a worker's feet were beside the intended shulker block,
but its hitbox slightly overlapped that block. Restocking chose a rear supply
position, then retried the collision check without correcting that overlap.

The shared restock placement path now checks the worker's actual bounding box
and walks one rear block away from the existing supply approach before trying again. The target
is fixed; existing container ownership, pickup tracking, paired-chest facing
and host coordination are unchanged. A small clearance margin prevents the
walking arrival tolerance from leaving the body clipping the placement space.
The existing supported-route checks still apply.

`highwaySupplyCheck` covers barely overlapping adjacent feet, negative world
coordinates, every heading, re-entering an old placement spot, and clearance at
the walking arrival tolerance. Physical server placement still needs a live
retest with the updated client. The current host can remain on 0.7.36.
