# Chest-side flight damage followed by food reserve failure

Restart `06124afe-4e6a-4046-a284-8d80e05b8274` on 0.14.19 successfully deposited the 30 retained Grass kits and delivered another 215 kits in its rotation. It subsequently failed with `No nearby personal minecraft:enchanted_golden_apple cache or carried supply shulker; replenish consumables before continuing`. Its checkpoint retained one confirmed Grass pickup and no pending withdrawal, deposit or discard. The worker landed and reported full health/hunger.

This is distinct from the previous inventory-reconciliation failure. The food guard requests personal resupply when usable Auto Eat food reaches eight or fewer. No known nearby personal cache or carried pure supply shulker was available. Do not treat full hunger as evidence of a replenished inventory or remove this survival check.

The client recorded repeated server `flyIntoWall` damage while traversing the diagonal Grass chest chain, with changing health and absorption and Auto Gap enabled. Food consumption from this damage is strongly suggested, but the exact collision geometry/server calculation was not captured. Local planned swept-body checks alone therefore did not establish server-safe travel.

0.14.21 adds bounded feedback recovery: server-reported wall damage during a stash action brakes navigation, lands on verified support and prefers walking for 200 ticks. Flight can retry afterwards, and its planner adds a 0.12-block horizontal width margin. Landing retains the actual body/support geometry introduced in 0.14.18. Packet handling is dispatched on the client thread and scoped to the original world/player. The release includes 0.14.20's independent pickup-recovery changes; it does not bypass food or elytra guards. Offline checks exercise the policy and geometry, but the server-specific cause and effectiveness require live verification.

Operator was asked to replenish enchanted golden apples before the next transfer. The next restart must deposit the one retained Grass kit first rather than assume an empty inventory.

0.14.21 passed the full build and was installed on the Debian Unbans profile with a matching JAR hash; 0.14.19 was retained in its backup directory. Restart `e2048039-f4c5-4d9d-ae65-859c4d13be92` targets the one retained Grass kit first. After the operator confirmed food replenishment and the worker became grounded, it started successfully and approached Main's deposit chest. A complete collision-recovery replay is not yet verified.
