# Shared restocking and changing conditions

0.7.34 changes the native highway workflow's crew resource fallback. New requests
use ordinary donor restocking and direct access to the donor's placed shulker.
Ender chest inventories remain private: the donor first retrieves its shulker.
Only the placing worker mines and recovers the container. Requesters transfer
matching contents into their inventory and never claim the container itself.
Donors can also load whole surplus stacks into a carried empty shulker, keeping
their configured resource target. A donor without a suitable box is retried or
another donor is selected; loose stock cannot become a shared container by itself.

The shared coordinator forwards a donor's current container observation. Removing
that observation removes the advertised target. Attempts expire after 30 seconds;
failure releases the attempt, briefly cools down that donor, and permits another
source. Terminal delivery cannot retain the attempt indefinitely. Old issued-drop
receipts retain their existing reconciliation path, but new requests never create
donation rendezvous or throw transactions. Both participants must advertise the
new shared-supply capability.

Detached restocking no longer needs clearance acknowledgements from other crew
members. Before placing, suppliers can select supported positions farther back
on the completed highway when an unrelated player occupies the site. Supply
retries remain enabled after the third attempt. Players do not obstruct crew
walking, including during detached pickup. Actual block collisions still apply.
Reservation protection follows visible containers, their footing, and shulker
drops instead of fencing off an empty volume across the working crew's next row.

If an owned container and its matching drop are absent for 40 consecutive loaded
observations, the bot logs the resource as unavailable, retires that supply
obligation, and retries supplies. An unloaded chunk or a visible matching drop
resets the absence counter. This is the operator-requested loss policy, not proof
of inventory recovery. Visible drops remain recovery targets.
An accidental pickup by another crew member stays with that member and becomes
part of the inventory pool. It no longer locks the collector's inventory or starts
a separate shulker-return rendezvous.

Validation covers absence/reappearance/unloaded transitions, detached admission
without other members' acknowledgements, and authenticated host/worker socket
dispatch, live shared-container withdrawal, failure, and cancellation. Physical
Minecraft opening, inventory transfer, pickup and movement still need live testing.
Install matching 0.7.34 client and host builds for the new shared-resource fallback.
