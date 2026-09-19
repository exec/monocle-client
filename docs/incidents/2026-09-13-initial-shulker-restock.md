# Initial shulker-only restock deadlock

Observed at the start of the westbound -50,000 to -60,000 job on 2026-09-13.
All three workers were connected, receiving control messages and ticking. No
placement/break acknowledgments, cursors or owned containers were outstanding.
Two workers requested detachment; the third had detached but could not walk to
its assigned rear site. Progress stayed at zero.

The coordinator required positive road progress before a second worker could
detach while any other supplier existed. That is a circular dependency when
everyone starts with their building materials inside shulkers. The shared host
coordinator now accepts independent detachment at row zero too.

The first supplier's exact feet were (-49999.64178544834, 116,
0.4579810321312172). Its padded 0.72-wide sweep extended into column -50001,
outside its actual 0.60-wide footprint. The route checker required fully
verified floor under that padding. Removing the floor-only padding reproduces
and fixes this geometric false rejection; the old telemetry did not identify
the exact rejected block, so it cannot prove this was the only route blocker.
New `travelBlocker` telemetry reports rejected footing/clearance coordinates.
Collision checks retain their margin; actual footing must still be supported.

## Updated resupply policy (0.7.31)

- Cardinal five-wide highways use group pit stops. One explicit supply request
  detaches the active crew into their preferred lanes at the verified front.
  Each worker checks all configured inventory targets during that visit.
- Other widths and diagonal roads retain independent rear sites, three blocks
  apart. They no longer require road progress before another worker detaches.
- A recovery/offline detachment does not trigger a group pit stop.
- Lane pit stops walk, rather than taking off. Pickup yielding stays in-lane.
  Paired ender chests at the outer lane are placed inward to avoid railings.
- Container use is still serialized by the existing reservation/receipt system.
  Lane pickup clearance is 1.75 blocks; outsiders and nearby workers still
  block recovery when inside that margin. Ownership, accidental-pickup return,
  inventory acknowledgments and cancellation recovery are retained.
- Fully stocked workers can finish a pit stop without ever owning a reservation.

The socket regression starts three workers with zero road progress and requests
supplies. At width three all can independently detach to rows -3/-6/-9; at width
five one request detaches all to columns 1/3/5. It also checks container ownership
and cancellation. Client checks cover the recorded edge position, genuinely
unsupported footing and paired-chest positions in all cardinal directions.

Install matching clients before running the updated host: older clients reject
off-center service sites. This remains the native Highway workflow implementation
shared by standalone and in-game hosts, not launcher-only orchestration.
